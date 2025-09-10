/*
 * Copyright (C) 2013, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.dfs;

import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_MIN_BYTES_OBJ_SIZE_INDEX;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_PACK_SECTION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.Deflater;

import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.junit.JGitTestUtil;
import org.eclipse.jgit.junit.TestRng;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.util.IO;
import org.eclipse.jgit.util.RawParseUtils;
import org.junit.Before;
import org.junit.Test;

public class DfsInserterTest {
	InMemoryRepository db;

	@Before
	public void setUp() {
		db = new InMemoryRepository(new DfsRepositoryDescription("test"));
	}

	@Test
	public void testInserterDiscardsPack() throws IOException {
		try (ObjectInserter ins = db.newObjectInserter()) {
			ins.insert(Constants.OBJ_BLOB, Constants.encode("foo"));
			ins.insert(Constants.OBJ_BLOB, Constants.encode("bar"));
			assertEquals(0, db.getObjectDatabase().listPacks().size());
		}
		assertEquals(0, db.getObjectDatabase().listPacks().size());
	}

	@Test
	public void testReadFromInserterSmallObjects() throws IOException {
		try (ObjectInserter ins = db.newObjectInserter()) {
			ObjectId id1 = ins.insert(Constants.OBJ_BLOB,
					Constants.encode("foo"));
			ObjectId id2 = ins.insert(Constants.OBJ_BLOB,
					Constants.encode("bar"));
			assertEquals(0, db.getObjectDatabase().listPacks().size());

			try (ObjectReader reader = ins.newReader()) {
				assertSame(ins, reader.getCreatedFromInserter());
				assertEquals("foo", readString(reader.open(id1)));
				assertEquals("bar", readString(reader.open(id2)));
				assertEquals(0, db.getObjectDatabase().listPacks().size());
				ins.flush();
				assertEquals(1, db.getObjectDatabase().listPacks().size());
			}
		}
	}

	@Test
	public void testReadFromInserterLargerObjects() throws IOException {
		db.getObjectDatabase().getReaderOptions().setStreamFileThreshold(512);
		DfsBlockCache.reconfigure(new DfsBlockCacheConfig()
			.setBlockSize(512)
			.setBlockLimit(2048));

		byte[] data = new TestRng(JGitTestUtil.getName()).nextBytes(8192);
		try (DfsInserter ins = (DfsInserter) db.newObjectInserter()) {
			ins.setCompressionLevel(Deflater.NO_COMPRESSION);
			ObjectId id1 = ins.insert(Constants.OBJ_BLOB, data);
			assertEquals(0, db.getObjectDatabase().listPacks().size());

			try (ObjectReader reader = ins.newReader()) {
				assertSame(ins, reader.getCreatedFromInserter());
				assertTrue(Arrays.equals(data, readStream(reader.open(id1))));
				assertEquals(0, db.getObjectDatabase().listPacks().size());
			}
			ins.flush();

		}
		List<DfsPackDescription> packs = db.getObjectDatabase().listPacks();
		assertEquals(1, packs.size());
		assertTrue(packs.get(0).getFileSize(PackExt.PACK) > 2048);
	}

	@Test
	public void testReadFromFallback() throws IOException {
		try (ObjectInserter ins = db.newObjectInserter()) {
			ObjectId id1 = ins.insert(Constants.OBJ_BLOB,
					Constants.encode("foo"));
			ins.flush();
			ObjectId id2 = ins.insert(Constants.OBJ_BLOB,
					Constants.encode("bar"));
			assertEquals(1, db.getObjectDatabase().listPacks().size());

			try (ObjectReader reader = ins.newReader()) {
				assertSame(ins, reader.getCreatedFromInserter());
				assertEquals("foo", readString(reader.open(id1)));
				assertEquals("bar", readString(reader.open(id2)));
				assertEquals(1, db.getObjectDatabase().listPacks().size());
			}
			ins.flush();
			assertEquals(2, db.getObjectDatabase().listPacks().size());
		}
	}

	@Test
	public void testReaderResolve() throws IOException {
		try (ObjectInserter ins = db.newObjectInserter()) {
			ObjectId id1 = ins.insert(Constants.OBJ_BLOB,
					Constants.encode("foo"));
			ins.flush();
			ObjectId id2 = ins.insert(Constants.OBJ_BLOB,
					Constants.encode("bar"));
			String abbr1 = ObjectId.toString(id1).substring(0, 4);
			String abbr2 = ObjectId.toString(id2).substring(0, 4);
			assertFalse(abbr1.equals(abbr2));

			try (ObjectReader reader = ins.newReader()) {
				assertSame(ins, reader.getCreatedFromInserter());
				Collection<ObjectId> objs;
				objs = reader.resolve(AbbreviatedObjectId.fromString(abbr1));
				assertEquals(1, objs.size());
				assertEquals(id1, objs.iterator().next());

				objs = reader.resolve(AbbreviatedObjectId.fromString(abbr2));
				assertEquals(1, objs.size());
				assertEquals(id2, objs.iterator().next());
			}
		}
	}

	@Test
	public void testGarbageSelectivelyVisible() throws IOException {
		ObjectId fooId;
		try (ObjectInserter ins = db.newObjectInserter()) {
			fooId = ins.insert(Constants.OBJ_BLOB, Constants.encode("foo"));
			ins.flush();
		}
		assertEquals(1, db.getObjectDatabase().listPacks().size());

		// Make pack 0 garbage.
		db.getObjectDatabase().listPacks().get(0).setPackSource(PackSource.UNREACHABLE_GARBAGE);

		// Default behavior should be that the database has foo, because we allow garbage objects.
		assertTrue(db.getObjectDatabase().has(fooId));
		// But we should not be able to see it if we pass the right args.
		assertFalse(db.getObjectDatabase().has(fooId, true));
	}

	@Test
	public void testInserterIgnoresUnreachable() throws IOException {
		ObjectId fooId;
		try (ObjectInserter ins = db.newObjectInserter()) {
			fooId = ins.insert(Constants.OBJ_BLOB, Constants.encode("foo"));
			ins.flush();
			assertEquals(1, db.getObjectDatabase().listPacks().size());

			// Make pack 0 garbage.
			db.getObjectDatabase().listPacks().get(0)
					.setPackSource(PackSource.UNREACHABLE_GARBAGE);

			// We shouldn't be able to see foo because it's garbage.
			assertFalse(db.getObjectDatabase().has(fooId, true));

			// But if we re-insert foo, it should become visible again.
			ins.insert(Constants.OBJ_BLOB, Constants.encode("foo"));
			ins.flush();
		}
		assertTrue(db.getObjectDatabase().has(fooId, true));

		// Verify that we have a foo in both packs, and 1 of them is garbage.
		try (DfsReader reader = new DfsReader(db.getObjectDatabase())) {
			DfsPackFile packs[] = db.getObjectDatabase().getPacks();
			Set<PackSource> pack_sources = new HashSet<>();

			assertEquals(2, packs.length);

			pack_sources.add(packs[0].getPackDescription().getPackSource());
			pack_sources.add(packs[1].getPackDescription().getPackSource());

			assertTrue(packs[0].hasObject(reader, fooId));
			assertTrue(packs[1].hasObject(reader, fooId));
			assertTrue(pack_sources.contains(PackSource.UNREACHABLE_GARBAGE));
			assertTrue(pack_sources.contains(PackSource.INSERT));
		}
	}

	@Test
	public void testNoDuplicates() throws IOException {
		byte[] contents = Constants.encode("foo");
		ObjectId fooId;
		try (ObjectInserter ins = db.newObjectInserter()) {
			fooId = ins.insert(Constants.OBJ_BLOB, contents);
			ins.flush();
		}
		assertEquals(1, db.getObjectDatabase().listPacks().size());

		try (ObjectInserter ins = db.newObjectInserter()) {
			ins.insert(Constants.OBJ_BLOB, Constants.encode("bar"));
			assertEquals(fooId, ins.insert(Constants.OBJ_BLOB, contents));
			ins.flush();
		}
		assertEquals(2, db.getObjectDatabase().listPacks().size());

		// Newer packs are first. Verify that foo is only in the second pack
		try (DfsReader reader = new DfsReader(db.getObjectDatabase())) {
			DfsPackFile packs[] = db.getObjectDatabase().getPacks();
			assertEquals(2, packs.length);
			DfsPackFile p1 = packs[0];
			assertEquals(PackSource.INSERT,
					p1.getPackDescription().getPackSource());
			assertFalse(p1.hasObject(reader, fooId));

			DfsPackFile p2 = packs[1];
			assertEquals(PackSource.INSERT,
					p2.getPackDescription().getPackSource());
			assertTrue(p2.hasObject(reader, fooId));
		}
	}

	@Test
	public void testObjectSizePopulated() throws IOException {
		// Blob
		byte[] contents = Constants.encode("foo");

		// Commit
		PersonIdent person = new PersonIdent("Committer a", "jgit@eclipse.org");
		CommitBuilder c = new CommitBuilder();
		c.setAuthor(person);
		c.setCommitter(person);
		c.setTreeId(ObjectId
				.fromString("45c4c6767a3945815371a7016532751dd558be40"));
		c.setMessage("commit message");

		// Tree
		TreeFormatter treeBuilder = new TreeFormatter(2);
		treeBuilder.append("filea", FileMode.REGULAR_FILE, ObjectId
				.fromString("45c4c6767a3945815371a7016532751dd558be40"));
		treeBuilder.append("fileb", FileMode.GITLINK, ObjectId
				.fromString("1c458e25ca624bb8d4735bec1379a4a29ba786d0"));

		// Tag
		TagBuilder tagBuilder = new TagBuilder();
		tagBuilder.setObjectId(
				ObjectId.fromString("c97fe131649e80de55bd153e9a8d8629f7ca6932"),
				Constants.OBJ_COMMIT);
		tagBuilder.setTag("short name");

		try (DfsInserter ins = (DfsInserter) db.newObjectInserter()) {
			ObjectId aBlob = ins.insert(Constants.OBJ_BLOB, contents);
			assertEquals(contents.length,
					ins.objectMap.get(aBlob).getFullSize());

			ObjectId aCommit = ins.insert(c);
			assertEquals(174, ins.objectMap.get(aCommit).getFullSize());

			ObjectId tree = ins.insert(treeBuilder);
			assertEquals(66, ins.objectMap.get(tree).getFullSize());

			ObjectId tag = ins.insert(tagBuilder);
			assertEquals(76, ins.objectMap.get(tag).getFullSize());
		}
	}

	@Test
	public void testObjectSizeIndexOnInsert() throws IOException {
		db.getConfig().setInt(CONFIG_PACK_SECTION, null,
				CONFIG_KEY_MIN_BYTES_OBJ_SIZE_INDEX, 0);
		db.getObjectDatabase().getReaderOptions().setUseObjectSizeIndex(true);

		byte[] contents = Constants.encode("foo");
		ObjectId fooId;
		try (ObjectInserter ins = db.newObjectInserter()) {
			fooId = ins.insert(Constants.OBJ_BLOB, contents);
			ins.flush();
		}

		DfsReader reader = db.getObjectDatabase().newReader();
		assertEquals(1, db.getObjectDatabase().listPacks().size());
		DfsPackFile insertPack = db.getObjectDatabase().getPacks()[0];
		assertEquals(PackSource.INSERT,
				insertPack.getPackDescription().getPackSource());
		assertTrue(insertPack.hasObjectSizeIndex(reader));
		assertEquals(contents.length, insertPack.getIndexedObjectSize(reader, fooId));
	}

	private static String readString(ObjectLoader loader) throws IOException {
		return RawParseUtils.decode(readStream(loader));
	}

	private static byte[] readStream(ObjectLoader loader) throws IOException {
		ByteBuffer bb = IO.readWholeStream(loader.openStream(), 64);
		byte[] buf = new byte[bb.remaining()];
		bb.get(buf);
		return buf;
	}
}
