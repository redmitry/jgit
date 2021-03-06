/*
 * Copyright (C) 2010, Google Inc.
 * and other copyright owners as documented in the project's IP log.
 *
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Distribution License v1.0 which
 * accompanies this distribution, is reproduced below, and is
 * available at http://www.eclipse.org/org/documents/edl-v10.php
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Eclipse Foundation, Inc. nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.eclipse.jgit.internal.storage.file;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;

import org.eclipse.jgit.internal.storage.pack.ObjectToPack;
import org.eclipse.jgit.internal.storage.pack.PackWriter;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectDatabase;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.util.FS;

abstract class FileObjectDatabase extends ObjectDatabase {
	static enum InsertLooseObjectResult {
		INSERTED, EXISTS_PACKED, EXISTS_LOOSE, FAILURE;
	}

	/** {@inheritDoc} */
	@Override
	public ObjectReader newReader() {
		return new WindowCursor(this);
	}

	/** {@inheritDoc} */
	@Override
	public ObjectDirectoryInserter newInserter() {
		return new ObjectDirectoryInserter(this, getConfig());
	}

	abstract void resolve(Set<ObjectId> matches, AbbreviatedObjectId id)
			throws IOException;

	abstract Config getConfig();

	abstract FS getFS();

	abstract Set<ObjectId> getShallowCommits() throws IOException;

	abstract void selectObjectRepresentation(PackWriter packer,
			ObjectToPack otp, WindowCursor curs) throws IOException;

        abstract Path getDirectoryPath();

        abstract Path filePathFor(AnyObjectId id);

	abstract ObjectLoader openObject(WindowCursor curs, AnyObjectId objectId)
			throws IOException;

	abstract long getObjectSize(WindowCursor curs, AnyObjectId objectId)
			throws IOException;

	abstract ObjectLoader openLooseObject(WindowCursor curs, AnyObjectId id)
			throws IOException;

	abstract InsertLooseObjectResult insertUnpackedObject(Path tmp,
			ObjectId id, boolean createDuplicate) throws IOException;
        
        abstract PackFile openPack(Path pack) throws IOException;

	abstract Collection<PackFile> getPacks();
}
