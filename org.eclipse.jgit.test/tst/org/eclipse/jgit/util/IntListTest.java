/*
 * Copyright (C) 2008, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.eclipse.jgit.util.IntList.IntComparator;
import org.junit.Test;

public class IntListTest {
	@Test
	public void testEmpty_DefaultCapacity() {
		final IntList i = new IntList();
		assertEquals(0, i.size());
		try {
			i.get(0);
			fail("Accepted 0 index on empty list");
		} catch (ArrayIndexOutOfBoundsException e) {
			assertTrue(true);
		}
	}

	@Test
	public void testEmpty_SpecificCapacity() {
		final IntList i = new IntList(5);
		assertEquals(0, i.size());
		try {
			i.get(0);
			fail("Accepted 0 index on empty list");
		} catch (ArrayIndexOutOfBoundsException e) {
			assertTrue(true);
		}
	}

	@Test
	public void testFilledWithRange() {
		IntList list = IntList.filledWithRange(-2, 13);
		assertEquals(15, list.size());
		for (int i = 0; i < list.size(); i++) {
			assertEquals(i - 2, list.get(i));
		}
	}

	@Test
	public void testAdd_SmallGroup() {
		final IntList i = new IntList();
		final int n = 5;
		for (int v = 0; v < n; v++)
			i.add(10 + v);
		assertEquals(n, i.size());

		for (int v = 0; v < n; v++)
			assertEquals(10 + v, i.get(v));
		try {
			i.get(n);
			fail("Accepted out of bound index on list");
		} catch (ArrayIndexOutOfBoundsException e) {
			assertTrue(true);
		}
	}

	@Test
	public void testAdd_ZeroCapacity() {
		final IntList i = new IntList(0);
		assertEquals(0, i.size());
		i.add(1);
		assertEquals(1, i.get(0));
	}

	@Test
	public void testAdd_LargeGroup() {
		final IntList i = new IntList();
		final int n = 500;
		for (int v = 0; v < n; v++)
			i.add(10 + v);
		assertEquals(n, i.size());

		for (int v = 0; v < n; v++)
			assertEquals(10 + v, i.get(v));
		try {
			i.get(n);
			fail("Accepted out of bound index on list");
		} catch (ArrayIndexOutOfBoundsException e) {
			assertTrue(true);
		}
	}

	@Test
	public void testFillTo0() {
		final IntList i = new IntList();
		i.fillTo(0, Integer.MIN_VALUE);
		assertEquals(0, i.size());
	}

	@Test
	public void testFillTo1() {
		final IntList i = new IntList();
		i.fillTo(1, Integer.MIN_VALUE);
		assertEquals(1, i.size());
		i.add(0);
		assertEquals(Integer.MIN_VALUE, i.get(0));
		assertEquals(0, i.get(1));
	}

	@Test
	public void testFillTo100() {
		final IntList i = new IntList();
		i.fillTo(100, Integer.MIN_VALUE);
		assertEquals(100, i.size());
		i.add(3);
		assertEquals(Integer.MIN_VALUE, i.get(99));
		assertEquals(3, i.get(100));
	}

	@Test
	public void testClear() {
		final IntList i = new IntList();
		final int n = 5;
		for (int v = 0; v < n; v++)
			i.add(10 + v);
		assertEquals(n, i.size());

		i.clear();
		assertEquals(0, i.size());
		try {
			i.get(0);
			fail("Accepted 0 index on empty list");
		} catch (ArrayIndexOutOfBoundsException e) {
			assertTrue(true);
		}
	}

	@Test
	public void testSet() {
		final IntList i = new IntList();
		i.add(1);
		assertEquals(1, i.size());
		assertEquals(1, i.get(0));

		i.set(0, 5);
		assertEquals(5, i.get(0));

		try {
			i.set(5, 5);
			fail("accepted set of 5 beyond end of list");
		} catch (ArrayIndexOutOfBoundsException e){
			assertTrue(true);
		}

		i.set(1, 2);
		assertEquals(2, i.size());
		assertEquals(2, i.get(1));
	}

	@Test
	public void testContains() {
		IntList i = new IntList();
		i.add(1);
		i.add(4);
		assertTrue(i.contains(1));
		assertTrue(i.contains(4));
		assertFalse(i.contains(2));
	}

	@Test
	public void testSort_byAbs() {
		IntList list = new IntList();
		list.add(-3);
		list.add(-2);
		list.add(0);
		list.add(1);
		list.add(4);
		list.add(1);
		list.sort(new AbsIntComparator());
		int[] expected = { 0, 1, 1, -2, -3, 4 };
		for (int i = 0; i < list.size(); i++) {
			assertEquals(expected[i], list.get(i));
		}
	}

	@Test
	public void testToString() {
		final IntList i = new IntList();
		i.add(1);
		assertEquals("[1]", i.toString());
		i.add(13);
		i.add(5);
		assertEquals("[1, 13, 5]", i.toString());
	}

	private static class AbsIntComparator implements IntComparator {
		private AbsIntComparator() {
		}

		@Override
		public int compare(int a, int b) {
			return Math.abs(a) - Math.abs(b);
		}
	}
}
