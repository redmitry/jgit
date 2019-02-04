/*
 * Copyright (C) 2010, 2012 Chris Aniszczyk <caniszczyk@gmail.com>
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
package org.eclipse.jgit.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuildIterator;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.events.WorkingTreeModifiedEvent;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;

/**
 * Remove files from the index and working directory (or optionally only from
 * the index).
 * <p>
 * It has setters for all supported options and arguments of this command and a
 * {@link #call()} method to finally execute the command. Each instance of this
 * class should only be used for one invocation of the command (means: one call
 * to {@link #call()}).
 * <p>
 * Examples (<code>git</code> is a {@link org.eclipse.jgit.api.Git} instance):
 * <p>
 * Remove file "test.txt" from both index and working directory:
 *
 * <pre>
 * git.rm().addFilepattern(&quot;test.txt&quot;).call();
 * </pre>
 * <p>
 * Remove file "new.txt" from the index (but not from the working directory):
 *
 * <pre>
 * git.rm().setCached(true).addFilepattern(&quot;new.txt&quot;).call();
 * </pre>
 *
 * @see <a href="http://www.kernel.org/pub/software/scm/git/docs/git-rm.html"
 *      >Git documentation about Rm</a>
 */
public class RmCommand extends GitCommand<DirCache> {

	private Collection<String> filepatterns;

	/** Only remove files from index, not from working directory */
	private boolean cached = false;

	/**
	 * Constructor for RmCommand.
	 *
	 * @param repo
	 *            the {@link org.eclipse.jgit.lib.Repository}
	 */
	public RmCommand(Repository repo) {
		super(repo);
		filepatterns = new LinkedList<>();
	}

	/**
	 * Add file name pattern of files to be removed
	 *
	 * @param filepattern
	 *            repository-relative path of file to remove (with
	 *            <code>/</code> as separator)
	 * @return {@code this}
	 */
	public RmCommand addFilepattern(String filepattern) {
		checkCallable();
		filepatterns.add(filepattern);
		return this;
	}

	/**
	 * Only remove the specified files from the index.
	 *
	 * @param cached
	 *            {@code true} if files should only be removed from index,
	 *            {@code false} if files should also be deleted from the working
	 *            directory
	 * @return {@code this}
	 * @since 2.2
	 */
	public RmCommand setCached(boolean cached) {
		checkCallable();
		this.cached = cached;
		return this;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Executes the {@code Rm} command. Each instance of this class should only
	 * be used for one invocation of the command. Don't call this method twice
	 * on an instance.
	 */
	@Override
	public DirCache call() throws GitAPIException,
			NoFilepatternException {

		if (filepatterns.isEmpty())
			throw new NoFilepatternException(JGitText.get().atLeastOnePatternIsRequired);
		checkCallable();
		DirCache dc = null;

		List<String> actuallyDeletedFiles = new ArrayList<>();
		try (TreeWalk tw = new TreeWalk(repo)) {
			dc = repo.lockDirCache();
			DirCacheBuilder builder = dc.builder();
			tw.reset(); // drop the first empty tree, which we do not need here
			tw.setRecursive(true);
			tw.setFilter(PathFilterGroup.createFromStrings(filepatterns));
			tw.addTree(new DirCacheBuildIterator(builder));

			while (tw.next()) {
				if (!cached) {
					final FileMode mode = tw.getFileMode(0);
					if (mode.getObjectType() == Constants.OBJ_BLOB) {
						String relativePath = tw.getPathString();
						final Path path = repo.getWorkTreePath().resolve(relativePath);
						// Deleting a blob is simply a matter of removing
						// the file or symlink named by the tree entry.
						if (delete(path)) {
							actuallyDeletedFiles.add(relativePath);
						}
					}
				}
			}
			builder.commit();
			setCallable(false);
		} catch (IOException e) {
			throw new JGitInternalException(
					JGitText.get().exceptionCaughtDuringExecutionOfRmCommand, e);
		} finally {
			try {
				if (dc != null) {
					dc.unlock();
				}
			} finally {
				if (!actuallyDeletedFiles.isEmpty()) {
					repo.fireEvent(new WorkingTreeModifiedEvent(null,
							actuallyDeletedFiles));
				}
			}
		}

		return dc;
	}

	private boolean delete(Path p) {
		boolean deleted = false;
		while (p != null && !p.equals(repo.getWorkTreePath())) {
                        try {
                            Files.delete(p);
                        } catch(IOException ex) {
                            break;
                        }
			deleted = true;
			p = p.getParent();
		}
		return deleted;
	}

}
