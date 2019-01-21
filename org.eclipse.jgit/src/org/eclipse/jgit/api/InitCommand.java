/*
 * Copyright (C) 2010, Chris Aniszczyk <caniszczyk@gmail.com>
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.concurrent.Callable;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;

/**
 * Create an empty git repository or reinitialize an existing one
 *
 * @see <a href="http://www.kernel.org/pub/software/scm/git/docs/git-init.html"
 *      >Git documentation about init</a>
 */
public class InitCommand implements Callable<Git> {
	private Path directory;

	private Path gitDir;

	private boolean bare;

	private FS fs;

	/**
	 * {@inheritDoc}
	 * <p>
	 * Executes the {@code Init} command.
	 *
	 * @return a {@code Git} instance that owns the {@code Repository} that it
	 *         wraps.
	 */
	@Override
	public Git call() throws GitAPIException {
		try {
			RepositoryBuilder builder = new RepositoryBuilder();
			if (bare)
				builder.setBare();
			if (fs != null) {
				builder.setFS(fs);
			}
			builder.readEnvironment();
			if (gitDir != null)
				builder.setGitDir(gitDir);
			else
				gitDir = builder.getGitDirPath();
			if (directory != null) {
				if (bare)
					builder.setGitDir(directory);
				else {
					builder.setWorkTree(directory);
					if (gitDir == null)
						builder.setGitDir(directory.resolve(Constants.DOT_GIT));
				}
			} else if (builder.getGitDirPath() == null) {
				String dStr = SystemReader.getInstance()
						.getProperty("user.dir"); //$NON-NLS-1$
				if (dStr == null)
					dStr = "."; //$NON-NLS-1$
				Path d = bare ? Paths.get(dStr) : Paths.get(dStr, Constants.DOT_GIT);
				builder.setGitDir(d);
			} else {
				// directory was not set but gitDir was set
				if (!bare) {
					String dStr = SystemReader.getInstance().getProperty(
							"user.dir"); //$NON-NLS-1$
					if (dStr == null)
						dStr = "."; //$NON-NLS-1$
					builder.setWorkTree(Paths.get(dStr));
				}
			}
			Repository repository = builder.build();
			if (!repository.getObjectDatabase().exists())
				repository.create(bare);
			return new Git(repository, true);
		} catch (IOException e) {
			throw new JGitInternalException(e.getMessage(), e);
		}
	}

	/**
	 * @deprecated use {@link #setDirectory(Path)}
         * 
	 * @param directory
	 *            the directory to init to
	 * @return this instance
	 * @throws java.lang.IllegalStateException
	 *             if the combination of directory, gitDir and bare is illegal.
	 *             E.g. if for a non-bare repository directory and gitDir point
	 *             to the same directory of if for a bare repository both
	 *             directory and gitDir are specified
	 */
	public InitCommand setDirectory(File directory)
			throws IllegalStateException {
                return setDirectory(directory != null ? directory.toPath() : null);
	}

	/**
	 * The optional directory associated with the init operation. If no
	 * directory is set, we'll use the current directory
	 *
	 * @param directory
	 *            the directory to init to
	 * @return this instance
	 * @throws java.lang.IllegalStateException
	 *             if the combination of directory, gitDir and bare is illegal.
	 *             E.g. if for a non-bare repository directory and gitDir point
	 *             to the same directory of if for a bare repository both
	 *             directory and gitDir are specified
	 */
	public InitCommand setDirectory(Path directory)
			throws IllegalStateException {
		validateDirs(directory, gitDir, bare);
		this.directory = directory;
		return this;
	}

	/**
	 * @deprecated use {@link #setGitDir(Path)}
	 *
	 * @param gitDir
	 *            the repository meta directory
	 * @return this instance
	 * @throws java.lang.IllegalStateException
	 *             if the combination of directory, gitDir and bare is illegal.
	 *             E.g. if for a non-bare repository directory and gitDir point
	 *             to the same directory of if for a bare repository both
	 *             directory and gitDir are specified
	 * @since 3.6
	 */
	public InitCommand setGitDir(File gitDir)
			throws IllegalStateException {
                return setGitDir(gitDir.toPath());
	}

	/**
	 * Set the repository meta directory (.git)
	 *
	 * @param gitDir
	 *            the repository meta directory
	 * @return this instance
	 * @throws java.lang.IllegalStateException
	 *             if the combination of directory, gitDir and bare is illegal.
	 *             E.g. if for a non-bare repository directory and gitDir point
	 *             to the same directory of if for a bare repository both
	 *             directory and gitDir are specified
	 * @since 3.6
	 */
	public InitCommand setGitDir(Path gitDir)
			throws IllegalStateException {
		validateDirs(directory, gitDir, bare);
		this.gitDir = gitDir;
		return this;
	}

	private static void validateDirs(Path directory, Path gitDir, boolean bare)
			throws IllegalStateException {
		if (directory != null) {
			if (bare) {
				if (gitDir != null && !gitDir.equals(directory))
					throw new IllegalStateException(MessageFormat.format(
							JGitText.get().initFailedBareRepoDifferentDirs,
							gitDir, directory));
			} else {
				if (gitDir != null && gitDir.equals(directory))
					throw new IllegalStateException(MessageFormat.format(
							JGitText.get().initFailedNonBareRepoSameDirs,
							gitDir, directory));
			}
		}
	}

	/**
	 * Set whether the repository is bare or not
	 *
	 * @param bare
	 *            whether the repository is bare or not
	 * @throws java.lang.IllegalStateException
	 *             if the combination of directory, gitDir and bare is illegal.
	 *             E.g. if for a non-bare repository directory and gitDir point
	 *             to the same directory of if for a bare repository both
	 *             directory and gitDir are specified
	 * @return this instance
	 */
	public InitCommand setBare(boolean bare) {
		validateDirs(directory, gitDir, bare);
		this.bare = bare;
		return this;
	}

	/**
	 * Set the file system abstraction to be used for repositories created by
	 * this command.
	 *
	 * @param fs
	 *            the abstraction.
	 * @return {@code this} (for chaining calls).
	 * @since 4.10
	 */
	public InitCommand setFs(FS fs) {
		this.fs = fs;
		return this;
	}
}
