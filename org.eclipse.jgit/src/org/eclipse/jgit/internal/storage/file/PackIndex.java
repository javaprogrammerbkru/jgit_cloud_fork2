/*
 * Copyright (C) 2008, Marek Zawirski <marek.zawirski@gmail.com>
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.Iterator;
import java.util.Set;

import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.UnsupportedPackIndexVersionException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.MutableObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdSet;
import org.eclipse.jgit.util.IO;
import org.eclipse.jgit.util.NB;
import org.eclipse.jgit.util.io.SilentFileInputStream;

/**
 * Access path to locate objects by {@link org.eclipse.jgit.lib.ObjectId} in a
 * {@link org.eclipse.jgit.internal.storage.file.Pack}.
 * <p>
 * Indexes are strictly redundant information in that we can rebuild all of the
 * data held in the index file from the on disk representation of the pack file
 * itself, but it is faster to access for random requests because data is stored
 * by ObjectId.
 * </p>
 */
public interface PackIndex
		extends Iterable<PackIndex.MutableEntry>, ObjectIdSet {
	/**
	 * Open an existing pack <code>.idx</code> file for reading.
	 * <p>
	 * The format of the file will be automatically detected and a proper access
	 * implementation for that format will be constructed and returned to the
	 * caller. The file may or may not be held open by the returned instance.
	 * </p>
	 *
	 * @param idxFile
	 *            existing pack .idx to read.
	 * @return access implementation for the requested file.
	 * @throws FileNotFoundException
	 *             the file does not exist.
	 * @throws java.io.IOException
	 *             the file exists but could not be read due to security errors,
	 *             unrecognized data version, or unexpected data corruption.
	 */
	static PackIndex open(File idxFile) throws IOException {
		try (SilentFileInputStream fd = new SilentFileInputStream(
				idxFile)) {
			return read(fd);
		} catch (FileNotFoundException e) {
			throw e;
		} catch (IOException ioe) {
			throw new IOException(
					MessageFormat.format(JGitText.get().unreadablePackIndex,
							idxFile.getAbsolutePath()),
					ioe);
		}
	}

	/**
	 * Read an existing pack index file from a buffered stream.
	 * <p>
	 * The format of the file will be automatically detected and a proper access
	 * implementation for that format will be constructed and returned to the
	 * caller. The file may or may not be held open by the returned instance.
	 *
	 * @param fd
	 *            stream to read the index file from. The stream must be
	 *            buffered as some small IOs are performed against the stream.
	 *            The caller is responsible for closing the stream.
	 * @return a copy of the index in-memory.
	 * @throws java.io.IOException
	 *             the stream cannot be read.
	 * @throws org.eclipse.jgit.errors.CorruptObjectException
	 *             the stream does not contain a valid pack index.
	 */
	static PackIndex read(InputStream fd) throws IOException,
			CorruptObjectException {
		final byte[] hdr = new byte[8];
		IO.readFully(fd, hdr, 0, hdr.length);
		if (isTOC(hdr)) {
			final int v = NB.decodeInt32(hdr, 4);
			switch (v) {
			case 2:
				return new PackIndexV2(fd);
			default:
				throw new UnsupportedPackIndexVersionException(v);
			}
		}
		return new PackIndexV1(fd, hdr);
	}

	private static boolean isTOC(byte[] h) {
		final byte[] toc = BasePackIndexWriter.TOC;
		for (int i = 0; i < toc.length; i++)
			if (h[i] != toc[i])
				return false;
		return true;
	}

	/**
	 * Determine if an object is contained within the pack file.
	 *
	 * @param id
	 *            the object to look for. Must not be null.
	 * @return true if the object is listed in this index; false otherwise.
	 */
	default boolean hasObject(AnyObjectId id) {
		return findOffset(id) != -1;
	}

	@Override
	default boolean contains(AnyObjectId id) {
		return findOffset(id) != -1;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Provide iterator that gives access to index entries. Note, that iterator
	 * returns reference to mutable object, the same reference in each call -
	 * for performance reason. If client needs immutable objects, it must copy
	 * returned object on its own.
	 * <p>
	 * Iterator returns objects in SHA-1 lexicographical order.
	 * </p>
	 */
	@Override
	Iterator<MutableEntry> iterator();

	/**
	 * Obtain the total number of objects described by this index.
	 *
	 * @return number of objects in this index, and likewise in the associated
	 *         pack that this index was generated from.
	 */
	long getObjectCount();

	/**
	 * Obtain the total number of objects needing 64 bit offsets.
	 *
	 * @return number of objects in this index using a 64 bit offset; that is an
	 *         object positioned after the 2 GB position within the file.
	 */
	long getOffset64Count();

	/**
	 * Get ObjectId for the n-th object entry returned by {@link #iterator()}.
	 * <p>
	 * This method is a constant-time replacement for the following loop:
	 *
	 * <pre>
	 * Iterator&lt;MutableEntry&gt; eItr = index.iterator();
	 * int curPosition = 0;
	 * while (eItr.hasNext() &amp;&amp; curPosition++ &lt; nthPosition)
	 * 	eItr.next();
	 * ObjectId result = eItr.next().toObjectId();
	 * </pre>
	 *
	 * @param nthPosition
	 *            position within the traversal of {@link #iterator()} that the
	 *            caller needs the object for. The first returned
	 *            {@link org.eclipse.jgit.internal.storage.file.PackIndex.MutableEntry}
	 *            is 0, the second is 1, etc.
	 * @return the ObjectId for the corresponding entry.
	 */
	ObjectId getObjectId(long nthPosition);

	/**
	 * Get ObjectId for the n-th object entry returned by {@link #iterator()}.
	 * <p>
	 * This method is a constant-time replacement for the following loop:
	 *
	 * <pre>
	 * Iterator&lt;MutableEntry&gt; eItr = index.iterator();
	 * int curPosition = 0;
	 * while (eItr.hasNext() &amp;&amp; curPosition++ &lt; nthPosition)
	 * 	eItr.next();
	 * ObjectId result = eItr.next().toObjectId();
	 * </pre>
	 *
	 * @param nthPosition
	 *            unsigned 32 bit position within the traversal of
	 *            {@link #iterator()} that the caller needs the object for. The
	 *            first returned
	 *            {@link org.eclipse.jgit.internal.storage.file.PackIndex.MutableEntry}
	 *            is 0, the second is 1, etc. Positions past 2**31-1 are
	 *            negative, but still valid.
	 * @return the ObjectId for the corresponding entry.
	 */
	default ObjectId getObjectId(int nthPosition) {
		if (nthPosition >= 0)
			return getObjectId((long) nthPosition);
		final int u31 = nthPosition >>> 1;
		final int one = nthPosition & 1;
		return getObjectId(((long) u31) << 1 | one);
	}

	/**
	 * Get offset in a pack for the n-th object entry returned by
	 * {@link #iterator()}.
	 *
	 * @param nthPosition
	 *            unsigned 32 bit position within the traversal of
	 *            {@link #iterator()} for which the caller needs the offset. The
	 *            first returned {@link MutableEntry} is 0, the second is 1,
	 *            etc. Positions past 2**31-1 are negative, but still valid.
	 * @return the offset in a pack for the corresponding entry.
	 */
	long getOffset(long nthPosition);

	/**
	 * Locate the file offset position for the requested object.
	 *
	 * @param objId
	 *            name of the object to locate within the pack.
	 * @return offset of the object's header and compressed content; -1 if the
	 *         object does not exist in this index and is thus not stored in the
	 *         associated pack.
	 */
	long findOffset(AnyObjectId objId);

	/**
	 * Locate the position of this id in the list of object-ids in the index
	 *
	 * @param objId
	 *            name of the object to locate within the index
	 * @return position of the object-id in the lexicographically ordered list
	 *         of ids stored in this index; -1 if the object does not exist in
	 *         this index and is thus not stored in the associated pack.
	 */
	int findPosition(AnyObjectId objId);

	/**
	 * Retrieve stored CRC32 checksum of the requested object raw-data
	 * (including header).
	 *
	 * @param objId
	 *            id of object to look for
	 * @return CRC32 checksum of specified object (at 32 less significant bits)
	 * @throws org.eclipse.jgit.errors.MissingObjectException
	 *             when requested ObjectId was not found in this index
	 * @throws java.lang.UnsupportedOperationException
	 *             when this index doesn't support CRC32 checksum
	 */
	long findCRC32(AnyObjectId objId)
			throws MissingObjectException, UnsupportedOperationException;

	/**
	 * Check whether this index supports (has) CRC32 checksums for objects.
	 *
	 * @return true if CRC32 is stored, false otherwise
	 */
	boolean hasCRC32Support();

	/**
	 * Find objects matching the prefix abbreviation.
	 *
	 * @param matches
	 *            set to add any located ObjectIds to. This is an output
	 *            parameter.
	 * @param id
	 *            prefix to search for.
	 * @param matchLimit
	 *            maximum number of results to return. At most this many
	 *            ObjectIds should be added to matches before returning.
	 * @throws java.io.IOException
	 *             the index cannot be read.
	 */
	void resolve(Set<ObjectId> matches, AbbreviatedObjectId id,
				 int matchLimit) throws IOException;

	/**
	 * Get pack checksum
	 *
	 * @return the checksum of the pack; caller must not modify it
	 * @since 5.5
	 */
	byte[] getChecksum();

	/**
	 * Represent mutable entry of pack index consisting of object id and offset
	 * in pack (both mutable).
	 *
	 */
	class MutableEntry {
		/** Buffer of the ObjectId visited by the EntriesIterator. */
		final MutableObjectId idBuffer = new MutableObjectId();

		/** Offset into the packfile of the current object. */
		long offset;

		/**
		 * Returns offset for this index object entry
		 *
		 * @return offset of this object in a pack file
		 */
		public long getOffset() {
			return offset;
		}

		/**
		 * Get hex string representation of the entry's object id
		 *
		 * @return hex string describing the object id of this entry.
		 */
		public String name() {
			return idBuffer.name();
		}

		/**
		 * Create a copy of the object id
		 *
		 * @return a copy of the object id.
		 */
		public ObjectId toObjectId() {
			return idBuffer.toObjectId();
		}

		/**
		 * Clone the entry
		 *
		 * @return a complete copy of this entry, that won't modify
		 */
		public MutableEntry cloneEntry() {
			final MutableEntry r = new MutableEntry();
			r.idBuffer.fromObjectId(idBuffer);
			r.offset = offset;
			return r;
		}

		/**
		 * Similar to {@link Comparable#compareTo(Object)}, using only the
		 * object id in the entry.
		 *
		 * @param other
		 *            Another mutable entry (probably from another index)
		 *
		 * @return a negative integer, zero, or a positive integer as this
		 *         object is less than, equal to, or greater than the specified
		 *         object.
		 */
		public int compareBySha1To(MutableEntry other) {
			return idBuffer.compareTo(other.idBuffer);
		}

		/**
		 * Copy the current ObjectId to dest
		 * <p>
		 * Like {@link #toObjectId()}, but reusing the destination instead of
		 * creating a new ObjectId instance.
		 *
		 * @param dest
		 *            destination for the object id
		 */
		public void copyOidTo(MutableObjectId dest) {
			dest.fromObjectId(idBuffer);
		}
	}

	/**
	 * Base implementation of the iterator over index entries.
	 */
	abstract class EntriesIterator implements Iterator<MutableEntry> {
		private final long objectCount;

		private final MutableEntry entry = new MutableEntry();

		/** Counts number of entries accessed so far. */
		private long returnedNumber = 0;

		/**
		 * Construct an iterator that can move objectCount times forward.
		 *
		 * @param objectCount
		 *            the number of objects in the PackFile.
		 */
		protected EntriesIterator(long objectCount) {
			this.objectCount = objectCount;
		}

		@Override
		public boolean hasNext() {
			return returnedNumber < objectCount;
		}

		/**
		 * Implementation must update {@link #returnedNumber} before returning
		 * element.
		 */
		@Override
		public MutableEntry next() {
			readNext();
			returnedNumber++;
			return entry;
		}

		/**
		 * Used by subclasses to load the next entry into the MutableEntry.
		 * <p>
		 * Subclasses are expected to populate the entry with
		 * {@link #setIdBuffer} and {@link #setOffset}.
		 */
		protected abstract void readNext();

		/**
		 * Copies to the entry an {@link ObjectId} from the int buffer and
		 * position idx
		 *
		 * @param raw
		 *            the raw data
		 * @param idx
		 *            the index into {@code raw}
		 */
		protected void setIdBuffer(int[] raw, int idx) {
			entry.idBuffer.fromRaw(raw, idx);
		}

		/**
		 * Copies to the entry an {@link ObjectId} from the byte array at
		 * position idx.
		 *
		 * @param raw
		 *            the raw data
		 * @param idx
		 *            the index into {@code raw}
		 */
		protected void setIdBuffer(byte[] raw, int idx) {
			entry.idBuffer.fromRaw(raw, idx);
		}

		/**
		 * Sets the {@code offset} to the entry
		 *
		 * @param offset
		 *            the offset in the pack file
		 */
		protected void setOffset(long offset) {
			entry.offset = offset;
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}
	}
}
