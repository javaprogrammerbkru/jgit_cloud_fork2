/*
 * Copyright (C) 2023, Tencent.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.revwalk;

import static java.util.Arrays.asList;
import static org.eclipse.jgit.internal.storage.commitgraph.CommitGraph.EMPTY;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffConfig;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.internal.storage.file.GC;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.filter.MessageRevFilter;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.ChangedPathTreeFilter;
import org.eclipse.jgit.treewalk.filter.OrTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.junit.Test;

public class RevWalkCommitGraphTest extends RevWalkTestCase {

	private RevWalk rw;

	@Override
	public void setUp() throws Exception {
		super.setUp();
		rw = new RevWalk(db);
		mockSystemReader.setJGitConfig(new MockConfig());
	}

	@Test
	public void testParseHeaders() throws Exception {
		RevCommit c1 = commitFile("file1", "1", "master");

		RevCommit notParseInGraph = rw.lookupCommit(c1);
		rw.parseHeaders(notParseInGraph);
		assertFalse(notParseInGraph instanceof RevCommitCG);
		assertNotNull(notParseInGraph.getRawBuffer());
		assertEquals(Constants.COMMIT_GENERATION_UNKNOWN,
				notParseInGraph.getGeneration());

		enableAndWriteCommitGraph();

		reinitializeRevWalk();
		RevCommit parseInGraph = rw.lookupCommit(c1);
		parseInGraph.parseHeaders(rw);

		assertTrue(parseInGraph instanceof RevCommitCG);
		assertNotNull(parseInGraph.getRawBuffer());
		assertEquals(1, parseInGraph.getGeneration());
		assertEquals(notParseInGraph.getId(), parseInGraph.getId());
		assertEquals(notParseInGraph.getTree(), parseInGraph.getTree());
		assertEquals(notParseInGraph.getCommitTime(), parseInGraph.getCommitTime());
		assertArrayEquals(notParseInGraph.getParents(), parseInGraph.getParents());

		reinitializeRevWalk();
		rw.setRetainBody(false);
		RevCommit noBody = rw.lookupCommit(c1);
		noBody.parseHeaders(rw);

		assertTrue(noBody instanceof RevCommitCG);
		assertNull(noBody.getRawBuffer());
		assertEquals(1, noBody.getGeneration());
		assertEquals(notParseInGraph.getId(), noBody.getId());
		assertEquals(notParseInGraph.getTree(), noBody.getTree());
		assertEquals(notParseInGraph.getCommitTime(), noBody.getCommitTime());
		assertArrayEquals(notParseInGraph.getParents(), noBody.getParents());
	}

	@Test
	public void testParseCanonical() throws Exception {
		RevCommit c1 = commitFile("file1", "1", "master");
		enableAndWriteCommitGraph();

		RevCommit notParseInGraph = rw.lookupCommit(c1);
		rw.parseHeaders(notParseInGraph);

		reinitializeRevWalk();
		RevCommit parseInGraph = rw.lookupCommit(c1);
		parseInGraph.parseCanonical(rw, rw.getCachedBytes(c1));

		assertTrue(parseInGraph instanceof RevCommitCG);
		assertNotNull(parseInGraph.getRawBuffer());
		assertEquals(1, parseInGraph.getGeneration());
		assertEquals(notParseInGraph.getId(), parseInGraph.getId());
		assertEquals(notParseInGraph.getTree(), parseInGraph.getTree());
		assertEquals(notParseInGraph.getCommitTime(),
				parseInGraph.getCommitTime());
		assertArrayEquals(notParseInGraph.getParents(),
				parseInGraph.getParents());

		reinitializeRevWalk();
		rw.setRetainBody(false);
		RevCommit noBody = rw.lookupCommit(c1);
		noBody.parseCanonical(rw, rw.getCachedBytes(c1));

		assertTrue(noBody instanceof RevCommitCG);
		assertNull(noBody.getRawBuffer());
		assertEquals(1, noBody.getGeneration());
		assertEquals(notParseInGraph.getId(), noBody.getId());
		assertEquals(notParseInGraph.getTree(), noBody.getTree());
		assertEquals(notParseInGraph.getCommitTime(), noBody.getCommitTime());
		assertArrayEquals(notParseInGraph.getParents(), noBody.getParents());
	}

	@Test
	public void testInitializeShallowCommits() throws Exception {
		RevCommit c1 = commit(commit());
		branch(c1, "master");
		enableAndWriteCommitGraph();
		assertCommitCntInGraph(2);

		db.getObjectDatabase().setShallowCommits(Collections.singleton(c1));
		RevCommit parseInGraph = rw.lookupCommit(c1);
		parseInGraph.parseHeaders(rw);

		assertTrue(parseInGraph instanceof RevCommitCG);
		assertNotNull(parseInGraph.getRawBuffer());
		assertEquals(2, parseInGraph.getGeneration());
		assertEquals(0, parseInGraph.getParentCount());
	}

	@Test
	public void testTreeFilter() throws Exception {
		RevCommit c1 = commitFile("file1", "1", "master");
		RevCommit c2 = commitFile("file2", "2", "master");
		RevCommit c3 = commitFile("file1", "3", "master");
		RevCommit c4 = commitFile("file2", "4", "master");

		enableAndWriteCommitGraph();
		assertCommitCntInGraph(4);

		rw.markStart(rw.lookupCommit(c4));
		rw.setTreeFilter(AndTreeFilter.create(PathFilter.create("file1"),
				TreeFilter.ANY_DIFF));
		assertEquals(c3, rw.next());
		assertEquals(c1, rw.next());
		assertNull(rw.next());

		reinitializeRevWalk();
		rw.markStart(rw.lookupCommit(c4));
		rw.setTreeFilter(AndTreeFilter.create(PathFilter.create("file2"),
				TreeFilter.ANY_DIFF));
		assertEquals(c4, rw.next());
		assertEquals(c2, rw.next());
		assertNull(rw.next());
	}

	@Test
	public void testChangedPathFilter_allModify() throws Exception {
		RevCommit c1 = commit(tree(file("file1", blob("1"))));
		RevCommit c2 = commit(tree(file("file2", blob("2"))), c1);
		RevCommit c3 = commit(tree(file("file1", blob("3"))), c2);
		RevCommit c4 = commit(tree(file("file2", blob("4"))), c3);

		branch(c4, "master");

		enableAndWriteCommitGraph();

		TreeRevFilter trf = new TreeRevFilter(rw,
				ChangedPathTreeFilter.create("file1"));
		rw.markStart(rw.lookupCommit(c4));
		rw.setRevFilter(trf);
		assertEquals(c4, rw.next());
		assertEquals(c3, rw.next());
		assertEquals(c2, rw.next());
		assertEquals(c1, rw.next());
		assertNull(rw.next());

		// all commits modified file1 but c1 did not have a parent
		assertEquals(3, trf.getChangedPathFilterTruePositive());

		// No false positives
		assertEquals(0, trf.getChangedPathFilterFalsePositive());

		// No negatives because all 4 commits had modified file1
		assertEquals(0, trf.getChangedPathFilterNegative());
	}

	@Test
	public void testChangedPathFilter_someModify() throws Exception {
		RevCommit c1 = commit(tree(file("file1", blob("1"))));
		RevCommit c2 = commit(tree(file("file1", blob("1"))), c1);
		RevCommit c3 = commit(tree(file("file1", blob("2"))), c2);
		RevCommit c4 = commit(tree(file("file1", blob("1"))), c3);

		branch(c4, "master");

		enableAndWriteCommitGraph();

		TreeRevFilter trf = new TreeRevFilter(rw,
				ChangedPathTreeFilter.create("file1"));
		rw.markStart(rw.lookupCommit(c4));
		rw.setRevFilter(trf);
		assertEquals(c4, rw.next());
		assertEquals(c3, rw.next());
		assertEquals(c1, rw.next());
		assertNull(rw.next());

		// c4 and c3 modified file1. c1 did not have a parent
		assertEquals(2, trf.getChangedPathFilterTruePositive());

		// No false positives
		assertEquals(0, trf.getChangedPathFilterFalsePositive());

		// c2 did not modify file1
		assertEquals(1, trf.getChangedPathFilterNegative());
	}

	@Test
	public void testChangedPathFilterWithMultiPaths() throws Exception {
		RevCommit c1 = commit(tree(file("file1", blob("1"))));
		RevCommit c2 = commit(tree(file("file1", blob("2"))), c1);
		RevCommit c3 = commit(tree(file("file2", blob("3"))), c2);
		RevCommit c4 = commit(tree(file("file3", blob("4"))), c3);

		branch(c4, "master");

		enableAndWriteCommitGraph();

		TreeRevFilter trf = new TreeRevFilter(rw,
				ChangedPathTreeFilter.create("file1", "file2"));
		rw.markStart(rw.lookupCommit(c4));
		rw.setRevFilter(trf);
		assertEquals(c4, rw.next());
		assertEquals(c3, rw.next());
		assertEquals(c2, rw.next());
		assertEquals(c1, rw.next());
		assertNull(rw.next());

		// all commits have modified either file1 or file2, c1 did not have a
		// parent
		assertEquals(3, trf.getChangedPathFilterTruePositive());

		// No false positives
		assertEquals(0, trf.getChangedPathFilterFalsePositive());

		// No negative
		assertEquals(0, trf.getChangedPathFilterNegative());
	}

	@Test
	public void testChangedPathFilterWithFollowFilter() throws Exception {
		RevCommit c0 = commit(tree());
		RevCommit c1 = commit(tree(file("file", blob("contents"))), c0);
		RevCommit c2 = commit(tree(file("file", blob("contents")),
				file("unrelated", blob("unrelated change"))), c1);
		RevCommit c3 = commit(tree(file("renamed-file", blob("contents")),
				file("unrelated", blob("unrelated change"))), c2);
		RevCommit c4 = commit(
				tree(file("renamed-file", blob("contents")),
						file("unrelated", blob("another unrelated change"))),
				c3);
		branch(c4, "master");

		enableAndWriteCommitGraph();

		db.getConfig().setString(ConfigConstants.CONFIG_DIFF_SECTION, null,
				ConfigConstants.CONFIG_KEY_RENAMES, "true");

		TreeRevFilter trf = new TreeRevFilter(rw, FollowFilter
				.create("renamed-file", db.getConfig().get(DiffConfig.KEY)));
		rw.markStart(rw.lookupCommit(c4));
		rw.setRevFilter(trf);
		assertEquals(c3, rw.next());
		assertEquals(c1, rw.next());
		assertNull(rw.next());

		// Path "renamed-file" is in c3's bloom filter, and another path "file"
		// is in c1's bloom filter (we know of "file" because the rev walk
		// detected that "renamed-file" is a renaming of "file")
		assertEquals(2, trf.getChangedPathFilterTruePositive());

		// No false positives
		assertEquals(0, trf.getChangedPathFilterFalsePositive());

		// 2 commits that have exactly one parent and don't match path
		assertEquals(2, trf.getChangedPathFilterNegative());
	}

	@Test
	public void testChangedPathFilter_pathFilter_or_pathFilter_binaryOperation()
			throws Exception {
		RevCommit c1 = commit(tree(file("file1", blob("1"))));
		RevCommit c2 = commit(
				tree(file("file1", blob("1")), file("file2", blob("2"))), c1);
		RevCommit c3 = commit(tree(file("file2", blob("2"))), c2);
		RevCommit c4 = commit(
				tree(file("file2", blob("2")), file("file3", blob("3"))), c3);
		RevCommit c5 = commit(
				tree(file("file2", blob("2")), file("file3", blob("3"))), c4);

		branch(c5, "master");

		enableAndWriteCommitGraph();

		ChangedPathTreeFilter pf1 = ChangedPathTreeFilter.create("file1");
		ChangedPathTreeFilter pf2 = ChangedPathTreeFilter.create("file2");

		TreeFilter tf = OrTreeFilter
				.create(new ChangedPathTreeFilter[] { pf1, pf2 });

		TreeRevFilter trf = new TreeRevFilter(rw, tf);
		rw.markStart(rw.lookupCommit(c5));
		rw.setRevFilter(trf);
		assertEquals(c3, rw.next());
		assertEquals(c2, rw.next());
		assertEquals(c1, rw.next());
		assertNull(rw.next());

		// c2 and c3 has either file1 or file2, c1 is not counted as
		// ChangedPathFilter only applies to commits with 1 parent
		assertEquals(2, trf.getChangedPathFilterTruePositive());

		// No false positives
		assertEquals(0, trf.getChangedPathFilterFalsePositive());

		// c4 and c5 did not modify file1 or file2
		assertEquals(2, trf.getChangedPathFilterNegative());
	}

	@Test
	public void testChangedPathFilter_pathFilter_or_pathFilter_or_pathFilter_listOperation()
			throws Exception {
		RevCommit c1 = commit(tree(file("file1", blob("1"))));
		RevCommit c2 = commit(
				tree(file("file1", blob("1")), file("file2", blob("2"))), c1);
		RevCommit c3 = commit(tree(file("file2", blob("2"))), c2);
		RevCommit c4 = commit(tree(file("file3", blob("3"))), c3);
		RevCommit c5 = commit(tree(file("file3", blob("3"))), c4);

		branch(c5, "master");

		enableAndWriteCommitGraph();

		ChangedPathTreeFilter pf1 = ChangedPathTreeFilter.create("file1");
		ChangedPathTreeFilter pf2 = ChangedPathTreeFilter.create("file2");
		ChangedPathTreeFilter pf3 = ChangedPathTreeFilter.create("file3");

		TreeFilter tf = OrTreeFilter
				.create(new ChangedPathTreeFilter[] { pf1, pf2, pf3 });

		TreeRevFilter trf = new TreeRevFilter(rw, tf);
		rw.markStart(rw.lookupCommit(c5));
		rw.setRevFilter(trf);
		assertEquals(c4, rw.next());
		assertEquals(c3, rw.next());
		assertEquals(c2, rw.next());
		assertEquals(c1, rw.next());
		assertNull(rw.next());

		// c2 and c3 has either modified file1 or file2 or file3, c1 is not
		// counted as ChangedPathFilter only applies to commits with 1 parent
		assertEquals(3, trf.getChangedPathFilterTruePositive());

		// No false positives
		assertEquals(0, trf.getChangedPathFilterFalsePositive());

		// c5 does not modify either file1 or file2 or file3
		assertEquals(1, trf.getChangedPathFilterNegative());
	}

	@Test
	public void testChangedPathFilter_pathFilter_or_nonPathFilter_binaryOperation()
			throws Exception {
		RevCommit c1 = commit(tree(file("file1", blob("1"))));
		RevCommit c2 = commit(tree(file("file2", blob("2"))), c1);
		RevCommit c3 = commit(tree(file("file2", blob("3"))), c2);
		RevCommit c4 = commit(tree(file("file2", blob("3"))), c3);

		branch(c4, "master");

		enableAndWriteCommitGraph();

		ChangedPathTreeFilter pf = ChangedPathTreeFilter.create("file1");
		TreeFilter npf = TreeFilter.ANY_DIFF;

		TreeFilter tf = OrTreeFilter.create(new TreeFilter[] { pf, npf });

		TreeRevFilter trf = new TreeRevFilter(rw, tf);
		rw.markStart(rw.lookupCommit(c4));
		rw.setRevFilter(trf);
		assertEquals(c3, rw.next());
		assertEquals(c2, rw.next());
		assertEquals(c1, rw.next());
		assertNull(rw.next());

		// c2 modified file1, c3 defaulted positive due to ANY_DIFF, c1 is not
		// counted as ChangedPathFilter only applies to commits with 1 parent
		assertEquals(2, trf.getChangedPathFilterTruePositive());

		// c4 defaulted positive due to ANY_DIFF, but didn't no diff with its
		// parent c3
		assertEquals(1, trf.getChangedPathFilterFalsePositive());

		// No negative due to the OrTreeFilter
		assertEquals(0, trf.getChangedPathFilterNegative());
	}

	@Test
	public void testChangedPathFilter_nonPathFilter_or_nonPathFilter_binaryOperation()
			throws Exception {
		RevCommit c1 = commitFile("file1", "1", "master");
		RevCommit c2 = commitFile("file2", "2", "master");
		RevCommit c3 = commitFile("file3", "3", "master");
		RevCommit c4 = commitFile("file4", "4", "master");

		enableAndWriteCommitGraph();

		TreeFilter npf1 = TreeFilter.ANY_DIFF;
		TreeFilter npf2 = TreeFilter.ANY_DIFF;

		TreeFilter tf = OrTreeFilter.create(new TreeFilter[] { npf1, npf2 });

		TreeRevFilter trf = new TreeRevFilter(rw, tf);
		rw.markStart(rw.lookupCommit(c4));
		rw.setRevFilter(trf);
		assertEquals(c4, rw.next());
		assertEquals(c3, rw.next());
		assertEquals(c2, rw.next());
		assertEquals(c1, rw.next());
		assertNull(rw.next());

		// No true positives since there's no pathFilter
		assertEquals(0, trf.getChangedPathFilterTruePositive());

		// No false positives since there's no pathFilter
		assertEquals(0, trf.getChangedPathFilterFalsePositive());

		// No negative since there's no pathFilter
		assertEquals(0, trf.getChangedPathFilterNegative());
	}

	@Test
	public void testChangedPathFilter_pathFilter_and_pathFilter_binaryOperation()
			throws Exception {
		RevCommit c1 = commit(tree(file("file1", blob("1"))));
		RevCommit c2 = commit(tree(file("file2", blob("2"))), c1);

		branch(c2, "master");

		enableAndWriteCommitGraph();

		ChangedPathTreeFilter pf1 = ChangedPathTreeFilter.create("file1");
		ChangedPathTreeFilter pf2 = ChangedPathTreeFilter.create("file2");

		TreeFilter atf = AndTreeFilter
				.create(new ChangedPathTreeFilter[] { pf1, pf2 });
		TreeRevFilter trf = new TreeRevFilter(rw, atf);

		rw.markStart(rw.lookupCommit(c2));
		rw.setRevFilter(trf);

		assertNull(rw.next());

		// c1 is not counted as ChangedPathFilter only applies to commits with 1
		// parent
		assertEquals(0, trf.getChangedPathFilterTruePositive());

		// c2 has modified both file 1 and file2,
		// however nothing is returned from TreeWalk since a TreeHead
		// cannot be two paths at once
		assertEquals(1, trf.getChangedPathFilterFalsePositive());

		// No negatives
		assertEquals(0, trf.getChangedPathFilterNegative());
	}

	@Test
	public void testChangedPathFilter_pathFilter_and_pathFilter_and_pathFilter_listOperation()
			throws Exception {
		RevCommit c1 = commit(tree(file("file1", blob("1"))));
		RevCommit c2 = commit(tree(file("file2", blob("2"))), c1);
		RevCommit c3 = commit(tree(file("file3", blob("3"))), c2);

		branch(c3, "master");

		enableAndWriteCommitGraph();

		ChangedPathTreeFilter pf1 = ChangedPathTreeFilter.create("file1");
		ChangedPathTreeFilter pf2 = ChangedPathTreeFilter.create("file2");
		ChangedPathTreeFilter pf3 = ChangedPathTreeFilter.create("file3");

		TreeFilter tf = AndTreeFilter
				.create(new ChangedPathTreeFilter[] { pf1, pf2, pf3 });

		TreeRevFilter trf = new TreeRevFilter(rw, tf);
		rw.markStart(rw.lookupCommit(c3));
		rw.setRevFilter(trf);
		assertNull(rw.next());

		// c1 is not counted as ChangedPathFilter only applies to commits with 1
		// parent
		assertEquals(0, trf.getChangedPathFilterTruePositive());

		// No false positives
		assertEquals(0, trf.getChangedPathFilterFalsePositive());

		// c2 and c3 can not possibly have both file1, file2, and file3 as
		// treeHead at once
		assertEquals(2, trf.getChangedPathFilterNegative());
	}

	@Test
	public void testChangedPathFilter_pathFilter_and_nonPathFilter_binaryOperation()
			throws Exception {
		RevCommit c1 = commit(tree(file("file1", blob("1"))));
		RevCommit c2 = commit(tree(file("file1", blob("2"))), c1);
		RevCommit c3 = commit(tree(file("file1", blob("2"))), c2);

		branch(c3, "master");

		enableAndWriteCommitGraph();

		ChangedPathTreeFilter pf = ChangedPathTreeFilter.create("file1");
		TreeFilter npf = TreeFilter.ANY_DIFF;

		TreeFilter tf = AndTreeFilter.create(new TreeFilter[] { pf, npf });

		TreeRevFilter trf = new TreeRevFilter(rw, tf);
		rw.markStart(rw.lookupCommit(c3));
		rw.setRevFilter(trf);
		assertEquals(c2, rw.next());
		assertEquals(c1, rw.next());
		assertNull(rw.next());

		// c2 modified file1 and c1 is not counted as ChangedPathFilter only
		// applies to commits with 1 parent
		assertEquals(1, trf.getChangedPathFilterTruePositive());

		// No false positives
		assertEquals(0, trf.getChangedPathFilterFalsePositive());

		// c3 did not modify file1
		assertEquals(1, trf.getChangedPathFilterNegative());
	}

	@Test
	public void testChangedPathFilter_nonPathFilter_and_nonPathFilter_binaryOperation()
			throws Exception {
		RevCommit c1 = commitFile("file1", "1", "master");
		commitFile("file1", "1", "master");
		RevCommit c3 = commitFile("file3", "3", "master");
		RevCommit c4 = commitFile("file4", "4", "master");

		enableAndWriteCommitGraph();

		TreeFilter npf1 = TreeFilter.ANY_DIFF;
		TreeFilter npf2 = TreeFilter.ANY_DIFF;

		TreeFilter tf = AndTreeFilter.create(new TreeFilter[] { npf1, npf2 });

		TreeRevFilter trf = new TreeRevFilter(rw, tf);
		rw.markStart(rw.lookupCommit(c4));
		rw.setRevFilter(trf);
		assertEquals(c4, rw.next());
		assertEquals(c3, rw.next());
		assertEquals(c1, rw.next());
		assertNull(rw.next());

		// No true positives since there's no path
		assertEquals(0, trf.getChangedPathFilterTruePositive());

		// No false positives since there's no path
		assertEquals(0, trf.getChangedPathFilterFalsePositive());

		// No negative since there's no path
		assertEquals(0, trf.getChangedPathFilterNegative());
	}

	@Test
	public void testWalkWithCommitMessageFilter() throws Exception {
		RevCommit a = commit();
		RevCommit b = commitBuilder().parent(a)
				.message("The quick brown fox jumps over the lazy dog!")
				.create();
		RevCommit c = commitBuilder().parent(b).message("commit-c").create();
		branch(c, "master");

		enableAndWriteCommitGraph();
		assertCommitCntInGraph(3);

		rw.setRevFilter(MessageRevFilter.create("quick brown fox jumps"));
		rw.markStart(rw.lookupCommit(c));
		assertEquals(b, rw.next());
		assertNull(rw.next());
	}

	@Test
	public void testCommitsWalk() throws Exception {
		RevCommit c1 = commit();
		branch(c1, "commits/1");
		RevCommit c2 = commit(c1);
		branch(c2, "commits/2");
		RevCommit c3 = commit(c2);
		branch(c3, "commits/3");

		enableAndWriteCommitGraph();
		assertCommitCntInGraph(3);
		testRevWalkBehavior("commits/1", "commits/3");

		// add more commits
		RevCommit c4 = commit(c1);
		RevCommit c5 = commit(c4);
		RevCommit c6 = commit(c1);
		RevCommit c7 = commit(c6);

		RevCommit m1 = commit(c2, c4);
		branch(m1, "merge/1");
		RevCommit m2 = commit(c4, c6);
		branch(m2, "merge/2");
		RevCommit m3 = commit(c3, c5, c7);
		branch(m3, "merge/3");

		/*
		 * <pre>
		 * current graph structure:
		 *
		 *    __M3___
		 *   /   |   \
		 *  3 M1 5 M2 7
		 *  |/  \|/  \|
		 *  2    4    6
		 *  |___/____/
		 *  1
		 * </pre>
		 */
		enableAndWriteCommitGraph();
		reinitializeRevWalk();
		assertCommitCntInGraph(10);
		testRevWalkBehavior("merge/1", "merge/2");
		testRevWalkBehavior("merge/1", "merge/3");
		testRevWalkBehavior("merge/2", "merge/3");

		// add one more commit
		RevCommit c8 = commit(m3);
		branch(c8, "commits/8");

		/*
		 * <pre>
		 * current graph structure:
		 *       8
		 *       |
		 *    __M3___
		 *   /   |   \
		 *  3 M1 5 M2 7
		 *  |/  \|/  \|
		 *  2    4    6
		 *  |___/____/
		 *  1
		 * </pre>
		 */
		testRevWalkBehavior("commits/8", "merge/1");
		testRevWalkBehavior("commits/8", "merge/2");

		enableAndWriteCommitGraph();
		reinitializeRevWalk();
		assertCommitCntInGraph(11);
		testRevWalkBehavior("commits/8", "merge/1");
		testRevWalkBehavior("commits/8", "merge/2");
	}

	@Test
	public void testMergedInto() throws Exception {
		RevCommit c1 = commit();
		Ref branch1 = branch(c1, "commits/1");
		RevCommit c2 = commit(c1);
		Ref branch2 = branch(c2, "commits/2");
		RevCommit c3 = commit(c2);
		Ref branch3 = branch(c3, "commits/3");
		RevCommit c4 = commit(c1);
		Ref branch4 = branch(c4, "commits/4");
		RevCommit c5 = commit(c4);
		Ref branch5 = branch(c5, "commits/5");
		enableAndWriteCommitGraph();
		RevCommit c6 = commit(c1);
		Ref branch6 = branch(c6, "commits/6");
		RevCommit c7 = commit(c2, c4);
		Ref branch7 = branch(c7, "commits/7");
		RevCommit c8 = commit(c5);
		Ref branch8 = branch(c8, "commits/8");
		RevCommit c9 = commit(c4, c6);
		Ref branch9 = branch(c9, "commits/9");

		/*
		 * <pre>
		 * current graph structure:
		 *       8
		 *       |
		 *  3  7 5  9
		 *  |/  \|/  \
		 *  2    4    6
		 *  |___/____/
		 *  1
		 * </pre>
		 *
		 * [6, 7, 8, 9] are not in commit-graph.
		 */

		reinitializeRevWalk();
		assertFalse(isObjectIdInGraph(c9));
		assertRefsEquals(asList(branch9), allMergedInto(c9));

		assertFalse(isObjectIdInGraph(c8));
		assertRefsEquals(asList(branch8), allMergedInto(c8));

		assertFalse(isObjectIdInGraph(c7));
		assertRefsEquals(asList(branch7), allMergedInto(c7));

		assertFalse(isObjectIdInGraph(c6));
		assertRefsEquals(asList(branch6, branch9), allMergedInto(c6));

		assertTrue(isObjectIdInGraph(c5));
		assertRefsEquals(asList(branch5, branch8), allMergedInto(c5));

		assertTrue(isObjectIdInGraph(c4));
		assertRefsEquals(asList(branch4, branch5, branch7, branch8, branch9),
				allMergedInto(c4));

		assertTrue(isObjectIdInGraph(c3));
		assertRefsEquals(asList(branch3), allMergedInto(c3));

		assertTrue(isObjectIdInGraph(c2));
		assertRefsEquals(asList(branch2, branch3, branch7), allMergedInto(c2));

		assertTrue(isObjectIdInGraph(c1));
		assertRefsEquals(asList(branch1, branch2, branch3, branch4, branch5,
				branch6, branch7, branch8, branch9), allMergedInto(c1));
	}

	boolean isObjectIdInGraph(AnyObjectId id) {
		return rw.commitGraph().findGraphPosition(id) >= 0;
	}

	List<Ref> allMergedInto(RevCommit needle) throws IOException {
		List<Ref> refs = db.getRefDatabase().getRefs();
		return rw.getMergedInto(rw.lookupCommit(needle), refs);
	}

	void assertRefsEquals(List<Ref> expecteds, List<Ref> actuals) {
		assertEquals(expecteds.size(), actuals.size());
		Collections.sort(expecteds, Comparator.comparing(Ref::getName));
		Collections.sort(actuals, Comparator.comparing(Ref::getName));
		for (int i = 0; i < expecteds.size(); i++) {
			Ref expected = expecteds.get(i);
			Ref actual = actuals.get(i);
			assertEquals(expected.getName(), actual.getName());
			assertEquals(expected.getObjectId(), actual.getObjectId());
		}
	}

	void testRevWalkBehavior(String branch, String compare) throws Exception {
		assertCommits(
				travel(TreeFilter.ALL, RevFilter.MERGE_BASE, RevSort.NONE, true,
						branch, compare),
				travel(TreeFilter.ALL, RevFilter.MERGE_BASE, RevSort.NONE,
						false, branch, compare));

		assertCommits(
				travel(TreeFilter.ALL, RevFilter.ALL, RevSort.TOPO, true,
						branch),
				travel(TreeFilter.ALL, RevFilter.ALL, RevSort.TOPO, false,
						branch));

		assertCommits(
				travel(TreeFilter.ALL, RevFilter.ALL, RevSort.TOPO, true,
						compare),
				travel(TreeFilter.ALL, RevFilter.ALL, RevSort.TOPO, false,
						compare));

		assertCommits(
				travel(TreeFilter.ALL, RevFilter.ALL, RevSort.COMMIT_TIME_DESC,
						true, branch),
				travel(TreeFilter.ALL, RevFilter.ALL, RevSort.COMMIT_TIME_DESC,
						false, branch));

		assertCommits(
				travel(TreeFilter.ALL, RevFilter.ALL, RevSort.COMMIT_TIME_DESC,
						true, compare),
				travel(TreeFilter.ALL, RevFilter.ALL, RevSort.COMMIT_TIME_DESC,
						false, compare));
	}

	void assertCommitCntInGraph(int expect) {
		assertEquals(expect, rw.commitGraph().getCommitCnt());
	}

	void assertCommits(List<RevCommit> expect, List<RevCommit> actual) {
		assertEquals(expect.size(), actual.size());

		for (int i = 0; i < expect.size(); i++) {
			RevCommit c1 = expect.get(i);
			RevCommit c2 = actual.get(i);

			assertEquals(c1.getId(), c2.getId());
			assertEquals(c1.getTree(), c2.getTree());
			assertEquals(c1.getCommitTime(), c2.getCommitTime());
			assertArrayEquals(c1.getParents(), c2.getParents());
			assertArrayEquals(c1.getRawBuffer(), c2.getRawBuffer());
		}
	}

	Ref branch(RevCommit commit, String name) throws Exception {
		return Git.wrap(db).branchCreate().setName(name)
				.setStartPoint(commit.name()).call();
	}

	List<RevCommit> travel(TreeFilter treeFilter, RevFilter revFilter,
			RevSort revSort, boolean enableCommitGraph, String... starts)
			throws Exception {
		db.getConfig().setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_COMMIT_GRAPH, enableCommitGraph);

		try (RevWalk walk = new RevWalk(db)) {
			walk.setTreeFilter(treeFilter);
			walk.setRevFilter(revFilter);
			walk.sort(revSort);
			walk.setRetainBody(false);
			for (String start : starts) {
				walk.markStart(walk.lookupCommit(db.resolve(start)));
			}
			List<RevCommit> commits = new ArrayList<>();

			if (enableCommitGraph) {
				assertTrue(walk.commitGraph().getCommitCnt() > 0);
			} else {
				assertEquals(EMPTY, walk.commitGraph());
			}

			for (RevCommit commit : walk) {
				commits.add(commit);
			}
			return commits;
		}
	}

	void enableAndWriteCommitGraph() throws Exception {
		db.getConfig().setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_COMMIT_GRAPH, true);
		db.getConfig().setBoolean(ConfigConstants.CONFIG_GC_SECTION, null,
				ConfigConstants.CONFIG_KEY_WRITE_COMMIT_GRAPH, true);
		db.getConfig().setBoolean(ConfigConstants.CONFIG_GC_SECTION, null,
				ConfigConstants.CONFIG_KEY_WRITE_CHANGED_PATHS, true);
		GC gc = new GC(db);
		gc.gc().get();
	}

	private void reinitializeRevWalk() {
		rw.close();
		rw = new RevWalk(db);
	}

	private static final class MockConfig extends FileBasedConfig {
		private MockConfig() {
			super(null, null);
		}

		@Override
		public void load() throws IOException, ConfigInvalidException {
			// Do nothing
		}

		@Override
		public void save() throws IOException {
			// Do nothing
		}

		@Override
		public boolean isOutdated() {
			return false;
		}

		@Override
		public String toString() {
			return "MockConfig";
		}

		@Override
		public boolean getBoolean(final String section, final String name,
				final boolean defaultValue) {
			if (section.equals(ConfigConstants.CONFIG_COMMIT_GRAPH_SECTION)
					&& name.equals(
							ConfigConstants.CONFIG_KEY_READ_CHANGED_PATHS)) {
				return true;
			}
			return defaultValue;
		}
	}
}
