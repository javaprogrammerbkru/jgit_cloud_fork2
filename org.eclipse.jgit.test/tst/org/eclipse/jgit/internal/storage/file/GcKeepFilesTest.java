/*
 * Copyright (C) 2012, Christian Halstrick <christian.halstrick@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_BUILD_BITMAPS;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_PACK_KEPT_OBJECTS;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_PACK_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_REPACK_SECTION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Iterator;

import org.eclipse.jgit.internal.storage.file.PackIndex.MutableEntry;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.junit.TestRepository.BranchBuilder;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.storage.pack.PackConfig;
import org.junit.Test;

public class GcKeepFilesTest extends GcTestCase {
	private static final int COMMIT_AND_TREE_OBJECTS = 2;

	@Test
	public void testKeepFiles() throws Exception {
		BranchBuilder bb = tr.branch("refs/heads/master");
		bb.commit().add("A", "A").add("B", "B").create();
		stats = gc.getStatistics();
		assertEquals(4, stats.numberOfLooseObjects);
		assertEquals(0, stats.numberOfPackedObjects);
		assertEquals(0, stats.numberOfPackFiles);
		gc.gc().get();
		stats = gc.getStatistics();
		assertEquals(0, stats.numberOfLooseObjects);
		assertEquals(4, stats.numberOfPackedObjects);
		assertEquals(1, stats.numberOfPackFiles);

		Iterator<Pack> packIt = repo.getObjectDatabase().getPacks()
				.iterator();
		Pack singlePack = packIt.next();
		assertFalse(packIt.hasNext());
		PackFile keepFile = singlePack.getPackFile().create(PackExt.KEEP);
		assertFalse(keepFile.exists());
		assertTrue(keepFile.createNewFile());
		bb.commit().add("A", "A2").add("B", "B2").create();
		stats = gc.getStatistics();
		assertEquals(4, stats.numberOfLooseObjects);
		assertEquals(4, stats.numberOfPackedObjects);
		assertEquals(1, stats.numberOfPackFiles);
		PackFile bitmapFile = singlePack.getPackFile().create(PackExt.BITMAP_INDEX);
		assertTrue(keepFile.exists());
		assertTrue(bitmapFile.delete());
		gc.setPackKeptObjects(false);
		gc.gc().get();
		stats = gc.getStatistics();
		assertEquals(0, stats.numberOfLooseObjects);
		assertEquals(8, stats.numberOfPackedObjects);
		assertEquals(2, stats.numberOfPackFiles);
		assertEquals(1, stats.numberOfBitmaps);

		// check that no object is packed twice
		Iterator<Pack> packs = repo.getObjectDatabase().getPacks()
				.iterator();
		PackIndex ind1 = packs.next().getIndex();
		assertEquals(4, ind1.getObjectCount());
		PackIndex ind2 = packs.next().getIndex();
		assertEquals(4, ind2.getObjectCount());
		for (MutableEntry e: ind1)
			if (ind2.hasObject(e.toObjectId()))
				assertFalse(
						"the following object is in both packfiles: "
								+ e.toObjectId(),
						ind2.hasObject(e.toObjectId()));
	}

	@Test
	public void testKeptObjectsAreIncludedByDefault() throws Exception {
		testKeptObjectsAreIncluded();
	}

	@Test
	public void testKeptObjectsAreIncludedByDefaultWhenBuildBitmapsIsTrue()
			throws Exception {
		PackConfig packConfig = new PackConfig();
		Config repoConfig = repo.getObjectDatabase().getConfig();
		repoConfig.setBoolean(CONFIG_PACK_SECTION, null,
				CONFIG_KEY_BUILD_BITMAPS, true);
		packConfig.fromConfig(repoConfig);
		gc.setPackConfig(packConfig);

		testKeptObjectsAreIncluded();
	}

	@Test
	public void testKeptObjectsAreIncludedWhenPackKeptObjectsIsFalseButOverriddenViaCommandLine()
			throws Exception {
		PackConfig packConfig = new PackConfig();
		packConfig.setPackKeptObjects(false);
		gc.setPackConfig(packConfig);
		gc.setPackKeptObjects(true);

		testKeptObjectsAreIncluded();
	}

	@Test
	public void testKeptObjectsAreNotIncludedByDefaultWhenBuildBitmapsIsFalse()
			throws Exception {
		PackConfig packConfig = new PackConfig();
		packConfig.setBuildBitmaps(false);
		gc.setPackConfig(packConfig);

		testKeptObjectsAreNotIncluded();
	}

	@Test
	public void testKeptObjectsAreIncludedWhenBuildBitmapsIsFalseButPackKeptObjectsIsTrue()
			throws Exception {
		PackConfig packConfig = new PackConfig();
		Config repoConfig = repo.getObjectDatabase().getConfig();
		repoConfig.setBoolean(CONFIG_PACK_SECTION, null,
				CONFIG_KEY_BUILD_BITMAPS, false);
		repoConfig.setBoolean(CONFIG_REPACK_SECTION, null,
				CONFIG_KEY_PACK_KEPT_OBJECTS, true);
		packConfig.fromConfig(repoConfig);
		gc.setPackConfig(packConfig);

		testKeptObjectsAreIncluded();
	}

	@Test
	public void testKeptObjectsAreNotIncludedWhenPackKeptObjectsIsTrueButOverriddenViaCommandLine()
			throws Exception {
		PackConfig packConfig = new PackConfig();
		packConfig.setPackKeptObjects(true);
		gc.setPackConfig(packConfig);
		gc.setPackKeptObjects(false);

		testKeptObjectsAreNotIncluded();
	}

	@Test
	public void testKeptObjectsAreNotIncludedWhenPackKeptObjectsConfigIsFalse()
			throws Exception {
		PackConfig packConfig = new PackConfig();
		packConfig.setPackKeptObjects(false);
		gc.setPackConfig(packConfig);

		testKeptObjectsAreNotIncluded();
	}

	private void testKeptObjectsAreIncluded() throws Exception {
		BranchBuilder bb = tr.branch("refs/heads/master");
		ObjectId commitObjectInLockedPack = bb.commit().create().toObjectId();
		gc.gc().get();
		stats = gc.getStatistics();
		assertEquals(COMMIT_AND_TREE_OBJECTS, stats.numberOfPackedObjects);
		assertEquals(1, stats.numberOfPackFiles);
		assertTrue(getSinglePack().getPackFile().create(PackExt.KEEP)
				.createNewFile());

		bb.commit().create();
		gc.gc().get();
		stats = gc.getStatistics();
		assertEquals(2 * COMMIT_AND_TREE_OBJECTS + 1,
				stats.numberOfPackedObjects);
		assertEquals(2, stats.numberOfPackFiles);

		PackIndex lockedPackIdx = null;
		PackIndex newPackIdx = null;
		for (Pack pack : repo.getObjectDatabase().getPacks()) {
			if (pack.getObjectCount() == COMMIT_AND_TREE_OBJECTS) {
				lockedPackIdx = pack.getIndex();
			} else {
				newPackIdx = pack.getIndex();
			}
		}
		assertNotNull(lockedPackIdx);
		assertTrue(lockedPackIdx.hasObject(commitObjectInLockedPack));
		assertNotNull(newPackIdx);
		assertTrue(newPackIdx.hasObject(commitObjectInLockedPack));
	}

	private void testKeptObjectsAreNotIncluded() throws Exception {
		BranchBuilder bb = tr.branch("refs/heads/master");
		ObjectId commitObjectInLockedPack = bb.commit().create().toObjectId();
		gc.gc().get();
		stats = gc.getStatistics();
		assertEquals(COMMIT_AND_TREE_OBJECTS, stats.numberOfPackedObjects);
		assertEquals(1, stats.numberOfPackFiles);
		assertTrue(getSinglePack().getPackFile().create(PackExt.KEEP)
				.createNewFile());

		bb.commit().create();
		gc.gc().get();
		stats = gc.getStatistics();
		assertEquals(COMMIT_AND_TREE_OBJECTS + 1, stats.numberOfPackedObjects);
		assertEquals(2, stats.numberOfPackFiles);

		PackIndex lockedPackIdx = null;
		PackIndex newPackIdx = null;
		for (Pack pack : repo.getObjectDatabase().getPacks()) {
			if (pack.getObjectCount() == COMMIT_AND_TREE_OBJECTS) {
				lockedPackIdx = pack.getIndex();
			} else {
				newPackIdx = pack.getIndex();
			}
		}
		assertNotNull(lockedPackIdx);
		assertTrue(lockedPackIdx.hasObject(commitObjectInLockedPack));
		assertNotNull(newPackIdx);
		assertFalse(newPackIdx.hasObject(commitObjectInLockedPack));
	}

	private Pack getSinglePack() {
		Iterator<Pack> packIt = repo.getObjectDatabase().getPacks().iterator();
		Pack singlePack = packIt.next();
		assertFalse(packIt.hasNext());
		return singlePack;
	}
}
