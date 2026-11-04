/*
 * Copyright (C) 2008, Google Inc.
 * Copyright (C) 2006-2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Modified for VaultSync (2026-10-29, D-033, LESSONS L-036): no pooling.
 * The original class from JGit 5.13.5 is removed from the jgit jar at build
 * time (gradle/jgit-patch.gradle.kts) and replaced by this one.
 */

package org.eclipse.jgit.lib;

import java.util.zip.Inflater;

/**
 * Creates zlib based inflaters as necessary for object decompression.
 * <p>
 * VaultSync: the JGit original keeps up to four released inflaters for reuse.
 * On Android, {@code java.util.zip.InflaterInputStream.close()} calls
 * {@code end()} on the inflater even when the caller supplied it, and JGit
 * hands pooled inflaters to such streams when it streams objects larger than
 * {@code streamFileThreshold}. Returning such an inflater to the pool then
 * throws ("Inflater has been closed"), or a later caller gets an ended one.
 * This version never pools: {@link #get()} always creates a new inflater and
 * {@link #release(Inflater)} only ends it. The public API is unchanged.
 */
public class InflaterCache {
	/**
	 * Obtain an Inflater for decompression.
	 * <p>
	 * Inflaters obtained through this class should be returned by
	 * {@link #release(Inflater)} so that their native memory is freed promptly.
	 *
	 * @return a new inflater. Never null.
	 */
	public static Inflater get() {
		return new Inflater(false);
	}

	/**
	 * Release an inflater previously obtained from this class.
	 *
	 * @param i
	 *            the inflater to release. May be null, in which case this
	 *            method does nothing. May already be ended (Android's
	 *            {@code InflaterInputStream.close()} ends it); ending it again
	 *            is a no-op.
	 */
	public static void release(Inflater i) {
		if (i != null) {
			i.end();
		}
	}

	private InflaterCache() {
		throw new UnsupportedOperationException();
	}
}
