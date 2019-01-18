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

package org.eclipse.jgit.lib;

import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_CORE_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_BARE;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_WORKTREE;
import static org.eclipse.jgit.lib.Constants.DOT_GIT;
import static org.eclipse.jgit.lib.Constants.GIT_ALTERNATE_OBJECT_DIRECTORIES_KEY;
import static org.eclipse.jgit.lib.Constants.GIT_CEILING_DIRECTORIES_KEY;
import static org.eclipse.jgit.lib.Constants.GIT_DIR_KEY;
import static org.eclipse.jgit.lib.Constants.GIT_INDEX_FILE_KEY;
import static org.eclipse.jgit.lib.Constants.GIT_OBJECT_DIRECTORY_KEY;
import static org.eclipse.jgit.lib.Constants.GIT_WORK_TREE_KEY;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.RepositoryCache.FileKey;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.RawParseUtils;
import org.eclipse.jgit.util.SystemReader;

/**
 * Base builder to customize repository construction.
 * <p>
 * Repository implementations may subclass this builder in order to add custom
 * repository detection methods.
 *
 * @param <B>
 *            type of the repository builder.
 * @param <R>
 *            type of the repository that is constructed.
 * @see RepositoryBuilder
 * @see FileRepositoryBuilder
 */
public class BaseRepositoryBuilder<B extends BaseRepositoryBuilder, R extends Repository> {
	private static boolean isSymRef(byte[] ref) {
		if (ref.length < 9)
			return false;
		return /**/ref[0] == 'g' //
				&& ref[1] == 'i' //
				&& ref[2] == 't' //
				&& ref[3] == 'd' //
				&& ref[4] == 'i' //
				&& ref[5] == 'r' //
				&& ref[6] == ':' //
				&& ref[7] == ' ';
	}

	private static Path getSymRef(Path workTree, Path dotGit, FS fs)
			throws IOException {
		byte[] content = Files.readAllBytes(dotGit);
		if (!isSymRef(content))
			throw new IOException(MessageFormat.format(
					JGitText.get().invalidGitdirRef, dotGit.toAbsolutePath()));

		int pathStart = 8;
		int lineEnd = RawParseUtils.nextLF(content, pathStart);
		while (content[lineEnd - 1] == '\n' ||
		       (content[lineEnd - 1] == '\r' && SystemReader.getInstance().isWindows()))
			lineEnd--;
		if (lineEnd == pathStart)
			throw new IOException(MessageFormat.format(
					JGitText.get().invalidGitdirRef, dotGit.toAbsolutePath()));

		String gitdirPath = RawParseUtils.decode(content, pathStart, lineEnd);
		Path gitdirFile = fs.resolve(workTree, gitdirPath);
		if (gitdirFile.isAbsolute()) {
			return gitdirFile;
                }
                return workTree.resolve(gitdirPath).toAbsolutePath().normalize();
	}

	private FS fs;

	private Path gitDir;

	private Path objectDirectory;

	private List<Path> alternateObjectDirectories;

	private Path indexFile;

	private Path workTree;

	/** Directories limiting the search for a Git repository. */
	private List<Path> ceilingDirectories;

	/** True only if the caller wants to force bare behavior. */
	private boolean bare;

	/** True if the caller requires the repository to exist. */
	private boolean mustExist;

	/** Configuration file of target repository, lazily loaded if required. */
	private Config config;

	/**
	 * Set the file system abstraction needed by this repository.
	 *
	 * @param fs
	 *            the abstraction.
	 * @return {@code this} (for chaining calls).
	 */
	public B setFS(FS fs) {
		this.fs = fs;
		return self();
	}

	/**
	 * Get the file system abstraction, or null if not set.
	 *
	 * @return the file system abstraction, or null if not set.
	 */
	public FS getFS() {
		return fs;
	}

	/**
	 * @deprecated use {@link #setGitDir(Path)}
	 *
	 * @param gitDir
	 *            {@code GIT_DIR}, the repository meta directory.
	 * @return {@code this} (for chaining calls).
	 */
	public B setGitDir(File gitDir) {
               return setGitDir(gitDir.toPath());
	}
        
	/**
	 * Set the Git directory storing the repository metadata.
	 * <p>
	 * The meta directory stores the objects, references, and meta files like
	 * {@code MERGE_HEAD}, or the index file. If {@code null} the path is
	 * assumed to be {@code workTree/.git}.
	 *
	 * @param gitDir
	 *            {@code GIT_DIR}, the repository meta directory.
	 * @return {@code this} (for chaining calls).
	 */
	public B setGitDir(Path gitDir) {
		this.gitDir = gitDir;
		this.config = null;
		return self();
	}

	/**
	 * @deprecated use {@link #getGitDirPath()}
	 *
	 * @return the meta data directory; null if not set.
	 */
	public File getGitDir() {
		return gitDir.toFile();
	}

	/**
	 * Get the meta data directory; null if not set.
	 *
	 * @return the meta data directory; null if not set.
	 */
	public Path getGitDirPath() {
		return gitDir;
	}

	/**
	 * @deprecated use {@link #setObjectDirectory(Path)}
	 *
	 * @param objectDirectory
	 *            {@code GIT_OBJECT_DIRECTORY}, the directory where the
	 *            repository's object files are stored.
	 * @return {@code this} (for chaining calls).
	 */
	public B setObjectDirectory(File objectDirectory) {
		return setObjectDirectory(objectDirectory.toPath());
	}

	/**
	 * Set the directory storing the repository's objects.
	 *
	 * @param objectDirectory
	 *            {@code GIT_OBJECT_DIRECTORY}, the directory where the
	 *            repository's object files are stored.
	 * @return {@code this} (for chaining calls).
	 */
	public B setObjectDirectory(Path objectDirectory) {
		this.objectDirectory = objectDirectory;
		return self();
	}

	/**
	 * @deprecated use {@link #getObjectDirectoryPath()}
	 *
	 * @return the object directory; null if not set.
	 */
	public File getObjectDirectory() {
		return objectDirectory.toFile();
	}

	/**
	 * Get the object directory; null if not set.
	 *
	 * @return the object directory; null if not set.
	 */
	public Path getObjectDirectoryPath() {
		return objectDirectory;
	}

	/**
	 * @deprecated use {@link #addAlternateObjectDirectory(Path)}
	 *
	 * @param other
	 *            another objects directory to search after the standard one.
	 * @return {@code this} (for chaining calls).
	 */
	public B addAlternateObjectDirectory(File other) {
                return addAlternateObjectDirectory(other != null ? other.toPath() : null);
	}

	/**
	 * Add an alternate object directory to the search list.
	 * <p>
	 * This setting handles one alternate directory at a time, and is provided
	 * to support {@code GIT_ALTERNATE_OBJECT_DIRECTORIES}.
	 *
	 * @param other
	 *            another objects directory to search after the standard one.
	 * @return {@code this} (for chaining calls).
	 */
	public B addAlternateObjectDirectory(Path other) {
		if (other != null) {
			if (alternateObjectDirectories == null)
				alternateObjectDirectories = new LinkedList<>();
			alternateObjectDirectories.add(other);
		}
		return self();
	}
        
	/**
	 * @deprecated use {@link #addAlternateObjectDirectoriesPaths(Collection)}
	 *
	 * @param inList
	 *            other object directories to search after the standard one. The
	 *            collection's contents is copied to an internal list.
	 * @return {@code this} (for chaining calls).
	 */
	public B addAlternateObjectDirectories(Collection<File> inList) {
		if (inList != null) {
			for (File path : inList)
				addAlternateObjectDirectory(path);
		}
		return self();
	}
        
	/**
	 * Add alternate object directories to the search list.
	 * <p>
	 * This setting handles several alternate directories at once, and is
	 * provided to support {@code GIT_ALTERNATE_OBJECT_DIRECTORIES}.
	 *
	 * @param inList
	 *            other object directories to search after the standard one. The
	 *            collection's contents is copied to an internal list.
	 * @return {@code this} (for chaining calls).
	 */
	public B addAlternateObjectDirectoriesPaths(Collection<Path> inList) {
		if (inList != null) {
			for (Path path : inList)
				addAlternateObjectDirectory(path);
		}
		return self();
	}

	/**
	 * @deprecated use {@link #addAlternateObjectDirectories(Path[])}
	 *
	 * @param inList
	 *            other object directories to search after the standard one. The
	 *            array's contents is copied to an internal list.
	 * @return {@code this} (for chaining calls).
	 */
	public B addAlternateObjectDirectories(File[] inList) {
		if (inList != null) {
			for (File path : inList)
				addAlternateObjectDirectory(path);
		}
		return self();
	}
        
	/**
	 * Add alternate object directories to the search list.
	 * <p>
	 * This setting handles several alternate directories at once, and is
	 * provided to support {@code GIT_ALTERNATE_OBJECT_DIRECTORIES}.
	 *
	 * @param inList
	 *            other object directories to search after the standard one. The
	 *            array's contents is copied to an internal list.
	 * @return {@code this} (for chaining calls).
	 */
	public B addAlternateObjectDirectories(Path[] inList) {
		if (inList != null) {
			for (Path path : inList)
				addAlternateObjectDirectory(path);
		}
		return self();
	}

	/**
	 * @deprecated use {@link #getAlternateObjectDirectoriesPaths()}
	 *
	 * @return ordered array of alternate directories; null if non were set.
	 */
	public File[] getAlternateObjectDirectories() {
                return alternateObjectDirectories != null 
                        ? alternateObjectDirectories.stream().map(Path::toFile).toArray(File[]::new)
                        : null;
	}
        
	/**
	 * Get ordered array of alternate directories; null if non were set.
	 *
	 * @return ordered array of alternate directories; null if non were set.
	 */
	public Path[] getAlternateObjectDirectoriesPaths() {
                return alternateObjectDirectories != null 
                        ? alternateObjectDirectories.toArray(new Path[0])
                        : null;
	}

	/**
	 * Force the repository to be treated as bare (have no working directory).
	 * <p>
	 * If bare the working directory aspects of the repository won't be
	 * configured, and will not be accessible.
	 *
	 * @return {@code this} (for chaining calls).
	 */
	public B setBare() {
		setIndexFile((Path)null);
		setWorkTree((Path)null);
		bare = true;
		return self();
	}

	/**
	 * Whether this repository was forced bare by {@link #setBare()}.
	 *
	 * @return true if this repository was forced bare by {@link #setBare()}.
	 */
	public boolean isBare() {
		return bare;
	}

	/**
	 * Require the repository to exist before it can be opened.
	 *
	 * @param mustExist
	 *            true if it must exist; false if it can be missing and created
	 *            after being built.
	 * @return {@code this} (for chaining calls).
	 */
	public B setMustExist(boolean mustExist) {
		this.mustExist = mustExist;
		return self();
	}

	/**
	 * Whether the repository must exist before being opened.
	 *
	 * @return true if the repository must exist before being opened.
	 */
	public boolean isMustExist() {
		return mustExist;
	}

	/**
	 * @deprecated use {@link #setWorkTree(Path)}
	 *
	 * @param workTree
	 *            {@code GIT_WORK_TREE}, the working directory of the checkout.
	 * @return {@code this} (for chaining calls).
	 */
	public B setWorkTree(File workTree) {
                return setWorkTree(workTree.toPath());
	}
        
	/**
	 * Set the top level directory of the working files.
	 *
	 * @param workTree
	 *            {@code GIT_WORK_TREE}, the working directory of the checkout.
	 * @return {@code this} (for chaining calls).
	 */
	public B setWorkTree(Path workTree) {
		this.workTree = workTree;
		return self();
	}

	/**
	 * @deprecated use {@link #getWorkTreePath()}
	 *
	 * @return the work tree directory, or null if not set.
	 */
	public File getWorkTree() {
		return workTree.toFile();
	}

	/**
	 * Get the work tree directory, or null if not set.
	 *
	 * @return the work tree directory, or null if not set.
	 */
	public Path getWorkTreePath() {
		return workTree;
	}

	/**
	 * @deprecated use {@link #setIndexFile(Path)}
	 *
	 * @param indexFile
	 *            {@code GIT_INDEX_FILE}, the index file location.
	 * @return {@code this} (for chaining calls).
	 */
	public B setIndexFile(File indexFile) {
		return setIndexFile(indexFile.toPath());
	}

	/**
	 * Set the local index file that is caching checked out file status.
	 * <p>
	 * The location of the index file tracking the status information for each
	 * checked out file in {@code workTree}. This may be null to assume the
	 * default {@code gitDiir/index}.
	 *
	 * @param indexFile
	 *            {@code GIT_INDEX_FILE}, the index file location.
	 * @return {@code this} (for chaining calls).
	 */
	public B setIndexFile(Path indexFile) {
		this.indexFile = indexFile;
		return self();
	}

	/**
	 * @deprecated use {@link #getIndexFilePath()}
	 *
	 * @return the index file location, or null if not set.
	 */
	public File getIndexFile() {
		return indexFile.toFile();
	}

	/**
	 * Get the index file location, or null if not set.
	 *
	 * @return the index file location, or null if not set.
	 */
	public Path getIndexFilePath() {
		return indexFile;
	}

	/**
	 * Read standard Git environment variables and configure from those.
	 * <p>
	 * This method tries to read the standard Git environment variables, such as
	 * {@code GIT_DIR} and {@code GIT_WORK_TREE} to configure this builder
	 * instance. If an environment variable is set, it overrides the value
	 * already set in this builder.
	 *
	 * @return {@code this} (for chaining calls).
	 */
	public B readEnvironment() {
		return readEnvironment(SystemReader.getInstance());
	}

	/**
	 * Read standard Git environment variables and configure from those.
	 * <p>
	 * This method tries to read the standard Git environment variables, such as
	 * {@code GIT_DIR} and {@code GIT_WORK_TREE} to configure this builder
	 * instance. If a property is already set in the builder, the environment
	 * variable is not used.
	 *
	 * @param sr
	 *            the SystemReader abstraction to access the environment.
	 * @return {@code this} (for chaining calls).
	 */
	public B readEnvironment(SystemReader sr) {
		if (getGitDirPath() == null) {
			String val = sr.getenv(GIT_DIR_KEY);
			if (val != null)
				setGitDir(Paths.get(val));
		}

		if (getObjectDirectoryPath() == null) {
			String val = sr.getenv(GIT_OBJECT_DIRECTORY_KEY);
			if (val != null)
				setObjectDirectory(Paths.get(val));
		}

		if (getAlternateObjectDirectoriesPaths() == null) {
			String val = sr.getenv(GIT_ALTERNATE_OBJECT_DIRECTORIES_KEY);
			if (val != null) {
				for (String path : val.split(File.pathSeparator))
					addAlternateObjectDirectory(Paths.get(path));
			}
		}

		if (getWorkTreePath() == null) {
			String val = sr.getenv(GIT_WORK_TREE_KEY);
			if (val != null)
				setWorkTree(Paths.get(val));
		}

		if (getIndexFilePath() == null) {
			String val = sr.getenv(GIT_INDEX_FILE_KEY);
			if (val != null)
				setIndexFile(Paths.get(val));
		}

		if (ceilingDirectories == null) {
			String val = sr.getenv(GIT_CEILING_DIRECTORIES_KEY);
			if (val != null) {
				for (String path : val.split(File.pathSeparator))
					addCeilingDirectory(Paths.get(path));
			}
		}

		return self();
	}

	/**
	 * @deprecated use {@link #addCeilingDirectory(Path)}
	 *
	 * @param root
	 *            a path to stop searching at; its parent will not be searched.
	 * @return {@code this} (for chaining calls).
	 */
	public B addCeilingDirectory(File root) {
                return addCeilingDirectory(root != null ? root.toPath() : null);
	}
        
	/**
	 * Add a ceiling directory to the search limit list.
	 * <p>
	 * This setting handles one ceiling directory at a time, and is provided to
	 * support {@code GIT_CEILING_DIRECTORIES}.
	 *
	 * @param root
	 *            a path to stop searching at; its parent will not be searched.
	 * @return {@code this} (for chaining calls).
	 */
	public B addCeilingDirectory(Path root) {
		if (root != null) {
			if (ceilingDirectories == null)
				ceilingDirectories = new LinkedList<>();
			ceilingDirectories.add(root);
		}
		return self();
	}

	/**
	 * @deprecated use {@link #addCeilingDirectoriesPaths(Collection)}
	 *
	 * @param inList
	 *            directory paths to stop searching at. The collection's
	 *            contents is copied to an internal list.
	 * @return {@code this} (for chaining calls).
	 */
	public B addCeilingDirectories(Collection<File> inList) {
		if (inList != null) {
			for (File path : inList)
				addCeilingDirectory(path);
		}
		return self();
	}

	/**
	 * Add ceiling directories to the search list.
	 * <p>
	 * This setting handles several ceiling directories at once, and is provided
	 * to support {@code GIT_CEILING_DIRECTORIES}.
	 *
	 * @param inList
	 *            directory paths to stop searching at. The collection's
	 *            contents is copied to an internal list.
	 * @return {@code this} (for chaining calls).
	 */
	public B addCeilingDirectoriesPaths(Collection<Path> inList) {
		if (inList != null) {
			for (Path path : inList)
				addCeilingDirectory(path);
		}
		return self();
	}

	/**
	 * @deprecated use {@link #addCeilingDirectories(Path[])}
	 *
	 * @param inList
	 *            directory paths to stop searching at. The array's contents is
	 *            copied to an internal list.
	 * @return {@code this} (for chaining calls).
	 */
	public B addCeilingDirectories(File[] inList) {
		if (inList != null) {
			for (File path : inList)
				addCeilingDirectory(path);
		}
		return self();
	}

	/**
	 * Add ceiling directories to the search list.
	 * <p>
	 * This setting handles several ceiling directories at once, and is provided
	 * to support {@code GIT_CEILING_DIRECTORIES}.
	 *
	 * @param inList
	 *            directory paths to stop searching at. The array's contents is
	 *            copied to an internal list.
	 * @return {@code this} (for chaining calls).
	 */
	public B addCeilingDirectories(Path[] inList) {
		if (inList != null) {
			for (Path path : inList)
				addCeilingDirectory(path);
		}
		return self();
	}

	/**
	 * Configure {@code GIT_DIR} by searching up the file system.
	 * <p>
	 * Starts from the current working directory of the JVM and scans up through
	 * the directory tree until a Git repository is found. Success can be
	 * determined by checking for {@code getGitDir() != null}.
	 * <p>
	 * The search can be limited to specific spaces of the local filesystem by
	 * {@link #addCeilingDirectory(Path)}, or inheriting the list through a
	 * prior call to {@link #readEnvironment()}.
	 *
	 * @return {@code this} (for chaining calls).
	 */
	public B findGitDir() {
		if (getGitDirPath() == null)
			findGitDir(Paths.get("").toAbsolutePath()); //$NON-NLS-1$
		return self();
	}

	/**
	 * @deprecated use {@link #findGitDir(Path)}
	 *
	 * @param current
	 *            directory to begin searching in.
	 * @return {@code this} (for chaining calls).
	 */
        public B findGitDir(File current) {
            return findGitDir(current.toPath());
        }

	/**
	 * Configure {@code GIT_DIR} by searching up the file system.
	 * <p>
	 * Starts from the supplied directory path and scans up through the parent
	 * directory tree until a Git repository is found. Success can be determined
	 * by checking for {@code getGitDir() != null}.
	 * <p>
	 * The search can be limited to specific spaces of the local filesystem by
	 * {@link #addCeilingDirectory(Path)}, or inheriting the list through a
	 * prior call to {@link #readEnvironment()}.
	 *
	 * @param current
	 *            directory to begin searching in.
	 * @return {@code this} (for chaining calls).
	 */
	public B findGitDir(Path current) {
		if (getGitDirPath() == null) {
			FS tryFS = safeFS();
			while (current != null) {
                                Path dir = current.resolve(DOT_GIT);
				if (FileKey.isGitRepository(dir, tryFS)) {
					setGitDir(dir);
					break;
				} else if (Files.isRegularFile(dir)) {
					try {
						setGitDir(getSymRef(current, dir, tryFS));
						break;
					} catch (IOException ignored) {
						// Continue searching if gitdir ref isn't found
					}
				} else if (FileKey.isGitRepository(current, tryFS)) {
					setGitDir(current);
					break;
				}

				current = current.getParent();
				if (current != null && ceilingDirectories != null
						&& ceilingDirectories.contains(current))
					break;
			}
		}
		return self();
	}

	/**
	 * Guess and populate all parameters not already defined.
	 * <p>
	 * If an option was not set, the setup method will try to default the option
	 * based on other options. If insufficient information is available, an
	 * exception is thrown to the caller.
	 *
	 * @return {@code this}
	 * @throws java.lang.IllegalArgumentException
	 *             insufficient parameters were set, or some parameters are
	 *             incompatible with one another.
	 * @throws java.io.IOException
	 *             the repository could not be accessed to configure the rest of
	 *             the builder's parameters.
	 */
	public B setup() throws IllegalArgumentException, IOException {
		requireGitDirOrWorkTree();
		setupGitDir();
		setupWorkTree();
		setupInternals();
		return self();
	}

	/**
	 * Create a repository matching the configuration in this builder.
	 * <p>
	 * If an option was not set, the build method will try to default the option
	 * based on other options. If insufficient information is available, an
	 * exception is thrown to the caller.
	 *
	 * @return a repository matching this configuration. The caller is
	 *         responsible to close the repository instance when it is no longer
	 *         needed.
	 * @throws java.lang.IllegalArgumentException
	 *             insufficient parameters were set.
	 * @throws java.io.IOException
	 *             the repository could not be accessed to configure the rest of
	 *             the builder's parameters.
	 */
	@SuppressWarnings({ "unchecked", "resource" })
	public R build() throws IOException {
		R repo = (R) new FileRepository(setup());
		if (isMustExist() && !repo.getObjectDatabase().exists())
			throw new RepositoryNotFoundException(getGitDirPath().toString());
		return repo;
	}

	/**
	 * Require either {@code gitDir} or {@code workTree} to be set.
	 */
	protected void requireGitDirOrWorkTree() {
		if (getGitDirPath() == null && getWorkTreePath() == null)
			throw new IllegalArgumentException(
					JGitText.get().eitherGitDirOrWorkTreeRequired);
	}

	/**
	 * Perform standard gitDir initialization.
	 *
	 * @throws java.io.IOException
	 *             the repository could not be accessed
	 */
	protected void setupGitDir() throws IOException {
		// No gitDir? Try to assume its under the workTree or a ref to another
		// location
		if (getGitDirPath() == null && getWorkTreePath() != null) {
                        Path dotGit = Paths.get(getWorkTreePath().toString(), DOT_GIT);
			if (!Files.isRegularFile(dotGit))
				setGitDir(dotGit);
			else
				setGitDir(getSymRef(getWorkTreePath(), dotGit, safeFS()));
		}
	}

	/**
	 * Perform standard work-tree initialization.
	 * <p>
	 * This is a method typically invoked inside of {@link #setup()}, near the
	 * end after the repository has been identified and its configuration is
	 * available for inspection.
	 *
	 * @throws java.io.IOException
	 *             the repository configuration could not be read.
	 */
	protected void setupWorkTree() throws IOException {
		if (getFS() == null)
			setFS(FS.DETECTED);

		// If we aren't bare, we should have a work tree.
		//
		if (!isBare() && getWorkTreePath() == null)
			setWorkTree(guessWorkTreeOrFail());

		if (!isBare()) {
			// If after guessing we're still not bare, we must have
			// a metadata directory to hold the repository. Assume
			// its at the work tree.
			//
			if (getGitDirPath() == null)
				setGitDir(getWorkTreePath().getParent());
			if (getIndexFilePath() == null)
				setIndexFile(getGitDirPath().resolve("index")); //$NON-NLS-1$
		}
	}

	/**
	 * Configure the internal implementation details of the repository.
	 *
	 * @throws java.io.IOException
	 *             the repository could not be accessed
	 */
	protected void setupInternals() throws IOException {
		if (getObjectDirectoryPath() == null && getGitDirPath() != null)
			setObjectDirectory(safeFS().resolve(getGitDirPath(), "objects")); //$NON-NLS-1$
	}

	/**
	 * Get the cached repository configuration, loading if not yet available.
	 *
	 * @return the configuration of the repository.
	 * @throws java.io.IOException
	 *             the configuration is not available, or is badly formed.
	 */
	protected Config getConfig() throws IOException {
		if (config == null)
			config = loadConfig();
		return config;
	}

	/**
	 * Parse and load the repository specific configuration.
	 * <p>
	 * The default implementation reads {@code gitDir/config}, or returns an
	 * empty configuration if gitDir was not set.
	 *
	 * @return the repository's configuration.
	 * @throws java.io.IOException
	 *             the configuration is not available.
	 */
	protected Config loadConfig() throws IOException {
		if (getGitDirPath() != null) {
			// We only want the repository's configuration file, and not
			// the user file, as these parameters must be unique to this
			// repository and not inherited from other files.
			//
			Path path = safeFS().resolve(getGitDirPath(), Constants.CONFIG);
			FileBasedConfig cfg = new FileBasedConfig(path, safeFS());
			try {
				cfg.load();
			} catch (ConfigInvalidException err) {
				throw new IllegalArgumentException(MessageFormat.format(
						JGitText.get().repositoryConfigFileInvalid, 
                                        path.toAbsolutePath(), err.getMessage()));
			}
			return cfg;
		} else {
			return new Config();
		}
	}

	private Path guessWorkTreeOrFail() throws IOException {
		final Config cfg = getConfig();

		// If set, core.worktree wins.
		//
		String path = cfg.getString(CONFIG_CORE_SECTION, null,
				CONFIG_KEY_WORKTREE);
		if (path != null)
			return safeFS().resolve(getGitDirPath(), path).toAbsolutePath().normalize();

		// If core.bare is set, honor its value. Assume workTree is
		// the parent directory of the repository.
		//
		if (cfg.getString(CONFIG_CORE_SECTION, null, CONFIG_KEY_BARE) != null) {
			if (cfg.getBoolean(CONFIG_CORE_SECTION, CONFIG_KEY_BARE, true)) {
				setBare();
				return null;
			}
			return getGitDirPath().getParent();
		}

		if (DOT_GIT.equals(getGitDirPath().getFileName().toString())) {
			// No value for the "bare" flag, but gitDir is named ".git",
			// use the parent of the directory
			//
			return getGitDirPath().getParent();
		}

		// We have to assume we are bare.
		//
		setBare();
		return null;
	}

	/**
	 * Get the configured FS, or {@link FS#DETECTED}.
	 *
	 * @return the configured FS, or {@link FS#DETECTED}.
	 */
	protected FS safeFS() {
		return getFS() != null ? getFS() : FS.DETECTED;
	}

	/**
	 * Get this object
	 *
	 * @return {@code this}
	 */
	@SuppressWarnings("unchecked")
	protected final B self() {
		return (B) this;
	}
}
