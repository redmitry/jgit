/*
 * Copyright (C) 2009, Google Inc.
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

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.internal.WorkQueue;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cache of active {@link org.eclipse.jgit.lib.Repository} instances.
 */
public class RepositoryCache {
	private final static Logger LOG = LoggerFactory
			.getLogger(RepositoryCache.class);

	private static final RepositoryCache cache = new RepositoryCache();

	/**
	 * Open an existing repository, reusing a cached instance if possible.
	 * <p>
	 * When done with the repository, the caller must call
	 * {@link org.eclipse.jgit.lib.Repository#close()} to decrement the
	 * repository's usage counter.
	 *
	 * @param location
	 *            where the local repository is. Typically a
	 *            {@link org.eclipse.jgit.lib.RepositoryCache.FileKey}.
	 * @return the repository instance requested; caller must close when done.
	 * @throws java.io.IOException
	 *             the repository could not be read (likely its core.version
	 *             property is not supported).
	 * @throws org.eclipse.jgit.errors.RepositoryNotFoundException
	 *             there is no repository at the given location.
	 */
	public static Repository open(Key location) throws IOException,
			RepositoryNotFoundException {
		return open(location, true);
	}

	/**
	 * Open a repository, reusing a cached instance if possible.
	 * <p>
	 * When done with the repository, the caller must call
	 * {@link org.eclipse.jgit.lib.Repository#close()} to decrement the
	 * repository's usage counter.
	 *
	 * @param location
	 *            where the local repository is. Typically a
	 *            {@link org.eclipse.jgit.lib.RepositoryCache.FileKey}.
	 * @param mustExist
	 *            If true, and the repository is not found, throws {@code
	 *            RepositoryNotFoundException}. If false, a repository instance
	 *            is created and registered anyway.
	 * @return the repository instance requested; caller must close when done.
	 * @throws java.io.IOException
	 *             the repository could not be read (likely its core.version
	 *             property is not supported).
	 * @throws RepositoryNotFoundException
	 *             There is no repository at the given location, only thrown if
	 *             {@code mustExist} is true.
	 */
	public static Repository open(Key location, boolean mustExist)
			throws IOException {
		return cache.openRepository(location, mustExist);
	}

	/**
	 * Register one repository into the cache.
	 * <p>
	 * During registration the cache automatically increments the usage counter,
	 * permitting it to retain the reference. A
	 * {@link org.eclipse.jgit.lib.RepositoryCache.FileKey} for the repository's
	 * {@link org.eclipse.jgit.lib.Repository#getDirectoryPath()} is used to index
	 * the repository in the cache.
	 * <p>
	 * If another repository already is registered in the cache at this
	 * location, the other instance is closed.
	 *
	 * @param db
	 *            repository to register.
	 */
	public static void register(Repository db) {
		if (db.getDirectoryPath() != null) {
			FileKey key = FileKey.exact(db.getDirectoryPath(), db.getFS());
			cache.registerRepository(key, db);
		}
	}

	/**
	 * Close and remove a repository from the cache.
	 * <p>
	 * Removes a repository from the cache, if it is still registered here, and
	 * close it.
	 *
	 * @param db
	 *            repository to unregister.
	 */
	public static void close(@NonNull Repository db) {
		if (db.getDirectoryPath() != null) {
			FileKey key = FileKey.exact(db.getDirectoryPath(), db.getFS());
			cache.unregisterAndCloseRepository(key);
		}
	}

	/**
	 * Remove a repository from the cache.
	 * <p>
	 * Removes a repository from the cache, if it is still registered here. This
	 * method will not close the repository, only remove it from the cache. See
	 * {@link org.eclipse.jgit.lib.RepositoryCache#close(Repository)} to remove
	 * and close the repository.
	 *
	 * @param db
	 *            repository to unregister.
	 * @since 4.3
	 */
	public static void unregister(Repository db) {
		if (db.getDirectoryPath() != null) {
			unregister(FileKey.exact(db.getDirectoryPath(), db.getFS()));
		}
	}

	/**
	 * Remove a repository from the cache.
	 * <p>
	 * Removes a repository from the cache, if it is still registered here. This
	 * method will not close the repository, only remove it from the cache. See
	 * {@link org.eclipse.jgit.lib.RepositoryCache#close(Repository)} to remove
	 * and close the repository.
	 *
	 * @param location
	 *            location of the repository to remove.
	 * @since 4.1
	 */
	public static void unregister(Key location) {
		cache.unregisterRepository(location);
	}

	/**
	 * Get the locations of all repositories registered in the cache.
	 *
	 * @return the locations of all repositories registered in the cache.
	 * @since 4.1
	 */
	public static Collection<Key> getRegisteredKeys() {
		return cache.getKeys();
	}

	static boolean isCached(@NonNull Repository repo) {
		Path gitDir = repo.getDirectoryPath();
		if (gitDir == null) {
			return false;
		}
		FileKey key = new FileKey(gitDir, repo.getFS());
		return cache.cacheMap.get(key) == repo;
	}

	/**
	 * Unregister all repositories from the cache.
	 */
	public static void clear() {
		cache.clearAll();
	}

	static void clearExpired() {
		cache.clearAllExpired();
	}

	static void reconfigure(RepositoryCacheConfig repositoryCacheConfig) {
		cache.configureEviction(repositoryCacheConfig);
	}

	private final ConcurrentHashMap<Key, Repository> cacheMap;

	private final Lock[] openLocks;

	private ScheduledFuture<?> cleanupTask;

	private volatile long expireAfter;

	private RepositoryCache() {
		cacheMap = new ConcurrentHashMap<>();
		openLocks = new Lock[4];
		for (int i = 0; i < openLocks.length; i++) {
			openLocks[i] = new Lock();
		}
		configureEviction(new RepositoryCacheConfig());
	}

	private void configureEviction(
			RepositoryCacheConfig repositoryCacheConfig) {
		expireAfter = repositoryCacheConfig.getExpireAfter();
		ScheduledThreadPoolExecutor scheduler = WorkQueue.getExecutor();
		synchronized (scheduler) {
			if (cleanupTask != null) {
				cleanupTask.cancel(false);
			}
			long delay = repositoryCacheConfig.getCleanupDelay();
			if (delay == RepositoryCacheConfig.NO_CLEANUP) {
				return;
			}
			cleanupTask = scheduler.scheduleWithFixedDelay(new Runnable() {
				@Override
				public void run() {
					try {
						cache.clearAllExpired();
					} catch (Throwable e) {
						LOG.error(e.getMessage(), e);
					}
				}
			}, delay, delay, TimeUnit.MILLISECONDS);
		}
	}

	private Repository openRepository(final Key location,
			final boolean mustExist) throws IOException {
		Repository db = cacheMap.get(location);
		if (db == null) {
			synchronized (lockFor(location)) {
				db = cacheMap.get(location);
				if (db == null) {
					db = location.open(mustExist);
					cacheMap.put(location, db);
				} else {
					db.incrementOpen();
				}
			}
		} else {
			db.incrementOpen();
		}
		return db;
	}

	private void registerRepository(Key location, Repository db) {
		try (Repository oldDb = cacheMap.put(location, db)) {
			// oldDb is auto-closed
		}
	}

	private Repository unregisterRepository(Key location) {
		return cacheMap.remove(location);
	}

	private boolean isExpired(Repository db) {
		return db != null && db.useCnt.get() <= 0
			&& (System.currentTimeMillis() - db.closedAt.get() > expireAfter);
	}

	private void unregisterAndCloseRepository(Key location) {
		synchronized (lockFor(location)) {
			Repository oldDb = unregisterRepository(location);
			if (oldDb != null) {
				oldDb.doClose();
			}
		}
	}

	private Collection<Key> getKeys() {
		return new ArrayList<>(cacheMap.keySet());
	}

	private void clearAllExpired() {
		for (Repository db : cacheMap.values()) {
			if (isExpired(db)) {
				RepositoryCache.close(db);
			}
		}
	}

	private void clearAll() {
		for (Key k : cacheMap.keySet()) {
			unregisterAndCloseRepository(k);
		}
	}

	private Lock lockFor(Key location) {
		return openLocks[(location.hashCode() >>> 1) % openLocks.length];
	}

	private static class Lock {
		// Used only for its monitor.
	}

	/**
	 * Abstract hash key for {@link RepositoryCache} entries.
	 * <p>
	 * A Key instance should be lightweight, and implement hashCode() and
	 * equals() such that two Key instances are equal if they represent the same
	 * Repository location.
	 */
	public static interface Key {
		/**
		 * Called by {@link RepositoryCache#open(Key)} if it doesn't exist yet.
		 * <p>
		 * If a repository does not exist yet in the cache, the cache will call
		 * this method to acquire a handle to it.
		 *
		 * @param mustExist
		 *            true if the repository must exist in order to be opened;
		 *            false if a new non-existent repository is permitted to be
		 *            created (the caller is responsible for calling create).
		 * @return the new repository instance.
		 * @throws IOException
		 *             the repository could not be read (likely its core.version
		 *             property is not supported).
		 * @throws RepositoryNotFoundException
		 *             There is no repository at the given location, only thrown
		 *             if {@code mustExist} is true.
		 */
		Repository open(boolean mustExist) throws IOException,
				RepositoryNotFoundException;
	}

	/** Location of a Repository, using the standard java.io.File API. */
	public static class FileKey implements Key {
		/**
		 * @deprecated use {@link #exact(Path, FS)}
		 *
		 * @param directory
		 *            location where the repository database is.
		 * @param fs
		 *            the file system abstraction which will be necessary to
		 *            perform certain file system operations.
		 * @return a key for the given directory.
		 * @see #lenient(File, FS)
		 */
		public static FileKey exact(File directory, FS fs) {
			return exact(directory != null ? directory.toPath() : null, fs);
		}

		/**
		 * Obtain a pointer to an exact location on disk.
		 * <p>
		 * No guessing is performed, the given location is exactly the GIT_DIR
		 * directory of the repository.
		 *
		 * @param directory
		 *            location where the repository database is.
		 * @param fs
		 *            the file system abstraction which will be necessary to
		 *            perform certain file system operations.
		 * @return a key for the given directory.
		 * @see #lenient(Path, FS)
		 */
		public static FileKey exact(Path directory, FS fs) {
			return new FileKey(directory, fs);
		}

		/**
		 * @deprecated use {@link #lenient(Path, FS)}
		 *
		 * @param directory
		 *            location where the repository database might be.
		 * @param fs
		 *            the file system abstraction which will be necessary to
		 *            perform certain file system operations.
		 * @return a key for the given directory.
		 * @see #exact(File, FS)
		 */
		public static FileKey lenient(File directory, FS fs) {
			return lenient(directory != null ? directory.toPath() : null, fs);
		}

		/**
		 * Obtain a pointer to a location on disk.
		 * <p>
		 * The method performs some basic guessing to locate the repository.
		 * Searched paths are:
		 * <ol>
		 * <li>{@code directory} // assume exact match</li>
		 * <li>{@code directory} + "/.git" // assume working directory</li>
		 * <li>{@code directory} + ".git" // assume bare</li>
		 * </ol>
		 *
		 * @param directory
		 *            location where the repository database might be.
		 * @param fs
		 *            the file system abstraction which will be necessary to
		 *            perform certain file system operations.
		 * @return a key for the given directory.
		 * @see #exact(Path, FS)
		 */
		public static FileKey lenient(Path directory, FS fs) {
			final Path gitdir = resolve(directory, fs);
			return new FileKey(gitdir != null ? gitdir : directory, fs);
		}
        
		private final Path path;
		private final FS fs;

		/**
		 * @param directory
		 *            exact location of the repository.
		 * @param fs
		 *            the file system abstraction which will be necessary to
		 *            perform certain file system operations.
		 */
		protected FileKey(File directory, FS fs) {
                       this(directory != null ? directory.toPath() : null, fs);
		}

		/**
		 * @param directory
		 *            exact location of the repository.
		 * @param fs
		 *            the file system abstraction which will be necessary to
		 *            perform certain file system operations.
		 */
		protected FileKey(Path directory, FS fs) {
			path = canonical(directory);
			this.fs = fs;
		}

		private static Path canonical(Path path) {
                        return path.toAbsolutePath().normalize();
		}

		/** 
                 * @deprecated use {@link #getFilePath()}
                 * 
                 * @return location supplied to the constructor. 
                 */
		public final File getFile() {
			return path.toFile();
		}

                /** @return location supplied to the constructor. */
		public final Path getFilePath() {
			return path;
		}
                
		@Override
		public Repository open(boolean mustExist) throws IOException {
			if (mustExist && !isGitRepository(path, fs))
				throw new RepositoryNotFoundException(path.toString());
			return new FileRepository(path);
		}

		@Override
		public int hashCode() {
			return path.hashCode();
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof FileKey && path.equals(((FileKey) o).path);
		}

		@Override
		public String toString() {
			return path.toString();
		}

		/**
		 * @deprecated use {@link #isGitRepository(Path, FS)}
		 *
		 * @param dir
		 *            the location of the directory to examine.
		 * @param fs
		 *            the file system abstraction which will be necessary to
		 *            perform certain file system operations.
		 * @return true if the directory "looks like" a Git repository; false if
		 *         it doesn't look enough like a Git directory to really be a
		 *         Git directory.
		 */
		public static boolean isGitRepository(File dir, FS fs) {
                        return isGitRepository(dir != null ? dir.toPath() : null, fs);
		}

		/**
		 * Guess if a directory contains a Git repository.
		 * <p>
		 * This method guesses by looking for the existence of some key files
		 * and directories.
		 *
		 * @param dir
		 *            the location of the directory to examine.
		 * @param fs
		 *            the file system abstraction which will be necessary to
		 *            perform certain file system operations.
		 * @return true if the directory "looks like" a Git repository; false if
		 *         it doesn't look enough like a Git directory to really be a
		 *         Git directory.
		 */
		public static boolean isGitRepository(Path dir, FS fs) {
			return Files.exists(fs.resolve(dir, "objects")) //$NON-NLS-1$
					&& Files.exists(fs.resolve(dir, "refs")) //$NON-NLS-1$
					&& isValidHead(dir.resolve(Constants.HEAD));
		}

		private static boolean isValidHead(Path head) {
			final String ref = readFirstLine(head);
			return ref != null
					&& (ref.startsWith("ref: refs/") || ObjectId.isId(ref)); //$NON-NLS-1$
		}

		private static String readFirstLine(Path head) {
			try {
                                return Files.lines(head, Charset.forName("UTF-8")).limit(1).findFirst().get();
			} catch (IOException e) {
                            // do nothing
                        }
                        
                        return null;
		}

		/**
		 * @deprecated use {@link #resolve(Path, FS)}
		 *
		 * @param directory
		 *            location to guess from. Several permutations are tried.
		 * @param fs
		 *            the file system abstraction which will be necessary to
		 *            perform certain file system operations.
		 * @return the actual directory location if a better match is found;
		 *         null if there is no suitable match.
		 */
		public static File resolve(File directory, FS fs) {
                        return resolve(directory != null ? directory.toPath() : null, fs).toFile();
		}
                
		/**
		 * Guess the proper path for a Git repository.
		 * <p>
		 * The method performs some basic guessing to locate the repository.
		 * Searched paths are:
		 * <ol>
		 * <li>{@code directory} // assume exact match</li>
		 * <li>{@code directory} + "/.git" // assume working directory</li>
		 * <li>{@code directory} + ".git" // assume bare</li>
		 * </ol>
		 *
		 * @param directory
		 *            location to guess from. Several permutations are tried.
		 * @param fs
		 *            the file system abstraction which will be necessary to
		 *            perform certain file system operations.
		 * @return the actual directory location if a better match is found;
		 *         null if there is no suitable match.
		 */
		public static Path resolve(Path directory, FS fs) {
			if (isGitRepository(directory, fs)) {
				return directory;
                        }
                        
                        Path dir = directory != null ? directory.resolve(Constants.DOT_GIT)
                                                           : Paths.get(Constants.DOT_GIT);
			if (isGitRepository(dir, fs)) {
				return dir;
                        }

                        dir = directory != null ? directory.resolveSibling(directory.getFileName() + Constants.DOT_GIT_EXT)
                                                : Paths.get(Constants.DOT_GIT_EXT);
			if (isGitRepository(dir, fs)) {
				return dir;
                        }

			return null;
		}
	}
}
