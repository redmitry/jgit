/*
 * Copyright (C) 2010, Google Inc.
 * Copyright (C) 2010, Matthias Sohn <matthias.sohn@sap.com>
 * Copyright (C) 2010, Jens Baumgart <jens.baumgart@sap.com>
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

package org.eclipse.jgit.util;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.text.MessageFormat;
import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.util.FS.Attributes;

/**
 * File Utilities
 */
public class FileUtils {

	/**
	 * Option to delete given {@code File}
	 */
	public static final int NONE = 0;

	/**
	 * Option to recursively delete given {@code File}
	 */
	public static final int RECURSIVE = 1;

	/**
	 * Option to retry deletion if not successful
	 */
	public static final int RETRY = 2;

	/**
	 * Option to skip deletion if file doesn't exist
	 */
	public static final int SKIP_MISSING = 4;

	/**
	 * Option not to throw exceptions when a deletion finally doesn't succeed.
	 * @since 2.0
	 */
	public static final int IGNORE_ERRORS = 8;

	/**
	 * Option to only delete empty directories. This option can be combined with
	 * {@link #RECURSIVE}
	 *
	 * @since 3.0
	 */
	public static final int EMPTY_DIRECTORIES_ONLY = 16;

	/**
	 * Safe conversion from {@link java.io.File} to {@link java.nio.file.Path}.
	 *
	 * @param f
	 *            {@code File} to be converted to {@code Path}
	 * @return the path represented by the file
	 * @throws java.io.IOException
	 *             in case the path represented by the file is not valid (
	 *             {@link java.nio.file.InvalidPathException})
	 * @since 4.10
	 */
	public static Path toPath(File f) throws IOException {
		try {
			return f != null ? f.toPath() : null;
		} catch (InvalidPathException ex) {
			throw new IOException(ex);
		}
	}

	/**
	 * @deprecated use {@link #delete(Path)}
	 *
	 * @param f
	 *            {@code File} to be deleted
	 * @throws java.io.IOException
	 *             if deletion of {@code f} fails. This may occur if {@code f}
	 *             didn't exist when the method was called. This can therefore
	 *             cause java.io.IOExceptions during race conditions when
	 *             multiple concurrent threads all try to delete the same file.
	 */
	public static void delete(File f) throws IOException {
		delete(toPath(f));
	}

	/**
	 * Delete file or empty folder
	 *
	 * @param f
	 *            {@code File} to be deleted
	 * @throws java.io.IOException
	 *             if deletion of {@code f} fails. This may occur if {@code f}
	 *             didn't exist when the method was called. This can therefore
	 *             cause java.io.IOExceptions during race conditions when
	 *             multiple concurrent threads all try to delete the same file.
	 */
	public static void delete(Path f) throws IOException {
		delete(f, NONE);
	}

	/**
	 * @deprecated use {@link #delete(Path, int)}
	 *
	 * @param f
	 *            {@code File} to be deleted
	 * @param options
	 *            deletion options, {@code RECURSIVE} for recursive deletion of
	 *            a subtree, {@code RETRY} to retry when deletion failed.
	 *            Retrying may help if the underlying file system doesn't allow
	 *            deletion of files being read by another thread.
	 * @throws java.io.IOException
	 *             if deletion of {@code f} fails. This may occur if {@code f}
	 *             didn't exist when the method was called. This can therefore
	 *             cause java.io.IOExceptions during race conditions when
	 *             multiple concurrent threads all try to delete the same file.
	 *             This exception is not thrown when IGNORE_ERRORS is set.
	 */
	public static void delete(File f, int options) throws IOException {
                delete(toPath(f), options);
	}

	/**
	 * Delete file or folder
	 *
	 * @param f
	 *            {@code File} to be deleted
	 * @param options
	 *            deletion options, {@code RECURSIVE} for recursive deletion of
	 *            a subtree, {@code RETRY} to retry when deletion failed.
	 *            Retrying may help if the underlying file system doesn't allow
	 *            deletion of files being read by another thread.
	 * @throws java.io.IOException
	 *             if deletion of {@code f} fails. This may occur if {@code f}
	 *             didn't exist when the method was called. This can therefore
	 *             cause java.io.IOExceptions during race conditions when
	 *             multiple concurrent threads all try to delete the same file.
	 *             This exception is not thrown when IGNORE_ERRORS is set.
	 */
	public static void delete(Path f, int options) throws IOException {
		FS fs = FS.DETECTED;
		if ((options & SKIP_MISSING) != 0 && !fs.exists(f))
			return;

		if ((options & RECURSIVE) != 0 && fs.isDirectory(f)) {
                        try (Stream<Path> stream = Files.list(f)) {
                                final Path[] items = stream.toArray(Path[]::new);

                                List<Path> files = new ArrayList<>();
                                List<Path> dirs = new ArrayList<>();
                                for (Path c : items) {
                                        if (Files.isDirectory(c)) {
                                                dirs.add(c);
                                        } else {
                                                files.add(c);
                                        }
                                }
                                // Try to delete files first, otherwise options
                                // EMPTY_DIRECTORIES_ONLY|RECURSIVE will delete empty
                                // directories before aborting, depending on order.
                                for (Path file : files) {
                                        delete(file, options);
                                }
                                for (Path d : dirs) {
                                        delete(d, options);
                                }
                        } catch (NoSuchFileException ex) {}
		}

		boolean delete = false;
		if ((options & EMPTY_DIRECTORIES_ONLY) != 0) {
			if (Files.isDirectory(f)) {
				delete = true;
			} else {
				if ((options & IGNORE_ERRORS) == 0)
					throw new IOException(MessageFormat.format(
							JGitText.get().deleteFileFailed,
							f.toAbsolutePath()));
			}
		} else {
			delete = true;
		}

		if (delete) {
                        int attempts = (options & RETRY) != 0 ? 10 : 1;
                        do {
                            try {
                                    Files.delete(f);
                                    return;
                            } catch (IOException ex) {
                            }

                            try {
                                    Thread.sleep(100);
                            } catch (InterruptedException e) {
                                    // ignore
                            }
                        } while (--attempts > 0);

			if ((options & IGNORE_ERRORS) == 0)
				throw new IOException(MessageFormat.format(
						JGitText.get().deleteFileFailed, f.toAbsolutePath()));
		}
	}

	/**
	 * @deprecated use {@link #rename(Path, Path)}
	 *
	 * @see FS#retryFailedLockFileCommit()
	 * @param src
	 *            the old {@code File}
	 * @param dst
	 *            the new {@code File}
	 * @throws java.io.IOException
	 *             if the rename has failed
	 * @since 3.0
	 */
	public static void rename(File src, File dst)
			throws IOException {
		rename(toPath(src), toPath(dst));
	}

	/**
	 * Rename a file or folder. If the rename fails and if we are running on a
	 * filesystem where it makes sense to repeat a failing rename then repeat
	 * the rename operation up to 9 times with 100ms sleep time between two
	 * calls. Furthermore if the destination exists and is directory hierarchy
	 * with only directories in it, the whole directory hierarchy will be
	 * deleted. If the target represents a non-empty directory structure, empty
	 * subdirectories within that structure may or may not be deleted even if
	 * the method fails. Furthermore if the destination exists and is a file
	 * then the file will be deleted and then the rename is retried.
	 * <p>
	 * This operation is <em>not</em> atomic.
	 * </p>
         * 
	 * @see FS#retryFailedLockFileCommit()
	 * @param src
	 *            the old {@code File}
	 * @param dst
	 *            the new {@code File}
	 * @throws java.io.IOException
	 *             if the rename has failed
	 * @since 3.0
	 */
	public static void rename(Path src, Path dst)
			throws IOException {
		rename(src, dst, StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * @deprecated use {@link #rename(Path, Path, CopyOption...)}
	 *
	 * @param src
	 *            the old file
	 * @param dst
	 *            the new file
	 * @param options
	 *            options to pass to
	 *            {@link java.nio.file.Files#move(java.nio.file.Path, java.nio.file.Path, CopyOption...)}
	 * @throws java.nio.file.AtomicMoveNotSupportedException
	 *             if file cannot be moved as an atomic file system operation
	 * @throws java.io.IOException
	 * @since 4.1
	 */
	public static void rename(final File src, final File dst,
			CopyOption... options)
					throws AtomicMoveNotSupportedException, IOException {
            rename(toPath(src), toPath(dst), options);
	}

	/**
	 * Rename a file or folder using the passed
	 * {@link java.nio.file.CopyOption}s. If the rename fails and if we are
	 * running on a filesystem where it makes sense to repeat a failing rename
	 * then repeat the rename operation up to 9 times with 100ms sleep time
	 * between two calls. Furthermore if the destination exists and is a
	 * directory hierarchy with only directories in it, the whole directory
	 * hierarchy will be deleted. If the target represents a non-empty directory
	 * structure, empty subdirectories within that structure may or may not be
	 * deleted even if the method fails. Furthermore if the destination exists
	 * and is a file then the file will be replaced if
	 * {@link java.nio.file.StandardCopyOption#REPLACE_EXISTING} has been set.
	 * If {@link java.nio.file.StandardCopyOption#ATOMIC_MOVE} has been set the
	 * rename will be done atomically or fail with an
	 * {@link java.nio.file.AtomicMoveNotSupportedException}
	 *
	 * @param src
	 *            the old file
	 * @param dst
	 *            the new file
	 * @param options
	 *            options to pass to
	 *            {@link java.nio.file.Files#move(java.nio.file.Path, java.nio.file.Path, CopyOption...)}
	 * @throws java.nio.file.AtomicMoveNotSupportedException
	 *             if file cannot be moved as an atomic file system operation
	 * @throws java.io.IOException
	 * @since 4.1
	 */
	public static void rename(final Path src, final Path dst,
			CopyOption... options)
					throws AtomicMoveNotSupportedException, IOException {
		int attempts = FS.DETECTED.retryFailedLockFileCommit() ? 10 : 1;
		while (--attempts >= 0) {
			try {
				Files.move(src, dst, options);
				return;
			} catch (AtomicMoveNotSupportedException e) {
				throw e;
			} catch (IOException e) {
				try {
                                        try {
                                                Files.delete(dst);
                                        } catch(IOException ex) {
                                                delete(dst, EMPTY_DIRECTORIES_ONLY | RECURSIVE);
                                        }
					// On *nix there is no try, you do or do not
					Files.move(src, dst, options);
					return;
				} catch (IOException e2) {
					// ignore and continue retry
				}
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				throw new IOException(
						MessageFormat.format(JGitText.get().renameFileFailed,
								src.toAbsolutePath(), dst.toAbsolutePath()));
			}
		}
		throw new IOException(
				MessageFormat.format(JGitText.get().renameFileFailed,
						src.toAbsolutePath(), dst.toAbsolutePath()));
	}

	/**
	 * @deprecated use {@link #mkdir(Path)}
	 *
	 * @param d
	 *            directory to be created
	 * @throws java.io.IOException
	 *             if creation of {@code d} fails. This may occur if {@code d}
	 *             did exist when the method was called. This can therefore
	 *             cause java.io.IOExceptions during race conditions when
	 *             multiple concurrent threads all try to create the same
	 *             directory.
	 */
	public static void mkdir(File d)
			throws IOException {
		mkdir(toPath(d));
	}

	/**
	 * Creates the directory named by this abstract pathname.
	 *
	 * @param d
	 *            directory to be created
	 * @throws java.io.IOException
	 *             if creation of {@code d} fails. This may occur if {@code d}
	 *             did exist when the method was called. This can therefore
	 *             cause java.io.IOExceptions during race conditions when
	 *             multiple concurrent threads all try to create the same
	 *             directory.
	 */
	public static void mkdir(Path d)
			throws IOException {
		mkdir(d, false);
	}

	/**
	 * @deprecated use {@link #mkdir(Path, boolean)}
	 *
	 * @param d
	 *            directory to be created
	 * @param skipExisting
	 *            if {@code true} skip creation of the given directory if it
	 *            already exists in the file system
	 * @throws java.io.IOException
	 *             if creation of {@code d} fails. This may occur if {@code d}
	 *             did exist when the method was called. This can therefore
	 *             cause java.io.IOExceptions during race conditions when
	 *             multiple concurrent threads all try to create the same
	 *             directory.
	 */
	public static void mkdir(File d, boolean skipExisting)
			throws IOException {
                mkdir(toPath(d), skipExisting);
	}

	/**
	 * Creates the directory named by this abstract pathname.
	 *
	 * @param d
	 *            directory to be created
	 * @param skipExisting
	 *            if {@code true} skip creation of the given directory if it
	 *            already exists in the file system
	 * @throws java.io.IOException
	 *             if creation of {@code d} fails. This may occur if {@code d}
	 *             did exist when the method was called. This can therefore
	 *             cause java.io.IOExceptions during race conditions when
	 *             multiple concurrent threads all try to create the same
	 *             directory.
	 */
	public static void mkdir(Path d, boolean skipExisting)
			throws IOException {
                try {
                    Files.createDirectory(d);
                } catch (FileAlreadyExistsException ex) {
                    if (!skipExisting || !Files.isDirectory(d)) {
			throw new IOException(MessageFormat.format(
					JGitText.get().mkDirFailed, d.toAbsolutePath()));                        
                    }
                }
	}

	/**
         * @deprecated use {@link #mkdirs(Path)}
	 *
	 * @param d
	 *            directory to be created
	 * @throws java.io.IOException
	 *             if creation of {@code d} fails. This may occur if {@code d}
	 *             did exist when the method was called. This can therefore
	 *             cause java.io.IOExceptions during race conditions when
	 *             multiple concurrent threads all try to create the same
	 *             directory.
	 */
	public static void mkdirs(File d) throws IOException {
		mkdirs(toPath(d));
	}

	/**
	 * Creates the directory named by this abstract pathname, including any
	 * necessary but nonexistent parent directories. Note that if this operation
	 * fails it may have succeeded in creating some of the necessary parent
	 * directories.
	 *
	 * @param d
	 *            directory to be created
	 * @throws java.io.IOException
	 *             if creation of {@code d} fails. This may occur if {@code d}
	 *             did exist when the method was called. This can therefore
	 *             cause java.io.IOExceptions during race conditions when
	 *             multiple concurrent threads all try to create the same
	 *             directory.
	 */
	public static void mkdirs(Path d) throws IOException {
		mkdirs(d, false);
	}
        
	/**
	 * @deprecated use {@link #mkdirs(Path, boolean)}
	 *
	 * @param d
	 *            directory to be created
	 * @param skipExisting
	 *            if {@code true} skip creation of the given directory if it
	 *            already exists in the file system
	 * @throws java.io.IOException
	 *             if creation of {@code d} fails. This may occur if {@code d}
	 *             did exist when the method was called. This can therefore
	 *             cause java.io.IOExceptions during race conditions when
	 *             multiple concurrent threads all try to create the same
	 *             directory.
	 */
	public static void mkdirs(File d, boolean skipExisting)
			throws IOException {
                mkdirs(toPath(d), skipExisting);
	}

	/**
	 * Creates the directory named by this abstract pathname, including any
	 * necessary but nonexistent parent directories. Note that if this operation
	 * fails it may have succeeded in creating some of the necessary parent
	 * directories.
	 *
	 * @param d
	 *            directory to be created
	 * @param skipExisting
	 *            if {@code true} skip creation of the given directory if it
	 *            already exists in the file system
	 * @throws java.io.IOException
	 *             if creation of {@code d} fails. This may occur if {@code d}
	 *             did exist when the method was called. This can therefore
	 *             cause java.io.IOExceptions during race conditions when
	 *             multiple concurrent threads all try to create the same
	 *             directory.
	 */
	public static void mkdirs(Path d, boolean skipExisting)
			throws IOException {
                if (skipExisting || !Files.exists(d)) {
                        Files.createDirectories(d);
                } else {
                        throw new IOException(MessageFormat.format(
                                JGitText.get().mkDirFailed, d.toAbsolutePath()));
                }
	}
        
	/**
	 * @deprecated use {@link #createNewFile(Path)}
	 *
	 * @param f
	 *            the file to be created
	 * @throws java.io.IOException
	 *             if the named file already exists or if an I/O error occurred
	 */
	public static void createNewFile(File f) throws IOException {
                createNewFile(toPath(f));
	}

	/**
	 * Atomically creates a new, empty file named by this abstract pathname if
	 * and only if a file with this name does not yet exist. The check for the
	 * existence of the file and the creation of the file if it does not exist
	 * are a single operation that is atomic with respect to all other
	 * filesystem activities that might affect the file.
	 * <p>
	 * Note: this method should not be used for file-locking, as the resulting
	 * protocol cannot be made to work reliably. The
	 * {@link java.nio.channels.FileLock} facility should be used instead.
	 *
	 * @param f
	 *            the file to be created
	 * @throws java.io.IOException
	 *             if the named file already exists or if an I/O error occurred
	 */
	public static void createNewFile(Path f) throws IOException {
            try {
                Files.createFile(f);
            } catch(IOException ex) {
			throw new IOException(MessageFormat.format(
					JGitText.get().createNewFileFailed, f));
            }
	}

	/**
	 * @deprecated use {@link #createSymLink(Path, String)}
	 *
	 * @param path
	 *            the path of the symbolic link to create
	 * @param target
	 *            the target of the symbolic link
	 * @return the path to the symbolic link
	 * @throws java.io.IOException
	 * @since 4.2
	 */
	public static Path createSymLink(File path, String target)
			throws IOException {
                return createSymLink(toPath(path), target);
	}

	/**
	 * Create a symbolic link
	 *
	 * @param path
	 *            the path of the symbolic link to create
	 * @param target
	 *            the target of the symbolic link
	 * @return the path to the symbolic link
	 * @throws java.io.IOException
	 * @since 4.2
	 */
	public static Path createSymLink(Path path, String target)
			throws IOException {
		if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			BasicFileAttributes attrs = Files.readAttributes(path,
					BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			if (attrs.isRegularFile() || attrs.isSymbolicLink()) {
				delete(path);
			} else {
				delete(path, EMPTY_DIRECTORIES_ONLY | RECURSIVE);
			}
		}
		if (SystemReader.getInstance().isWindows()) {
			target = target.replace('/', '\\');
		}
		Path nioTarget = toPath(new File(target));
		return Files.createSymbolicLink(path, nioTarget);
	}

	/**
	 * @deprecated use {@link #readSymLink(Path)}
	 *
	 * @param path
	 *            a {@link java.io.File} object.
	 * @return target path of the symlink, or null if it is not a symbolic link
	 * @throws java.io.IOException
	 * @since 3.0
	 */
	public static String readSymLink(File path) throws IOException {
                return readSymLink(path.toPath());
	}

	/**
	 * Read target path of the symlink.
	 *
	 * @param path
	 *            a {@link java.io.File} object.
	 * @return target path of the symlink, or null if it is not a symbolic link
	 * @throws java.io.IOException
	 * @since 3.0
	 */
	public static String readSymLink(Path path) throws IOException {
		Path target = Files.readSymbolicLink(path);
		String targetString = target.toString();
		if (SystemReader.getInstance().isWindows()) {
			targetString = targetString.replace('\\', '/');
		} else if (SystemReader.getInstance().isMacOS()) {
			targetString = Normalizer.normalize(targetString, Form.NFC);
		}
		return targetString;
	}

	/**
	 * @deprecated use {@link #createTempDir(String, String, Path)}
	 *
	 * @param prefix
	 *            prefix string
	 * @param suffix
	 *            suffix string
	 * @param dir
	 *            The parent dir, can be null to use system default temp dir.
	 * @return the temp dir created.
	 * @throws java.io.IOException
	 * @since 3.4
	 */
	public static File createTempDir(String prefix, String suffix, File dir)
			throws IOException {
                return createTempDir(prefix, suffix, toPath(dir)).toFile();
	}

	/**
	 * Create a temporary directory.
	 *
	 * @param prefix
	 *            prefix string
	 * @param suffix
	 *            suffix string
	 * @param dir
	 *            The parent dir, can be null to use system default temp dir.
	 * @return the temp dir created.
	 * @throws java.io.IOException
	 * @since 3.4
	 */
	public static Path createTempDir(String prefix, String suffix, Path dir)
			throws IOException {
		final int RETRIES = 1; // When something bad happens, retry once.
		for (int i = 0; i < RETRIES; i++) {
                        try {
                                final Path tmp;
                                if (dir == null) {
                                        tmp = Files.createTempFile(prefix, suffix);
                                } else {
                                        tmp = Files.createTempFile(dir, prefix, suffix);
                                }
                                Files.deleteIfExists(tmp);
                                Files.createDirectory(tmp);
                                
                                return tmp;
                        } catch(IOException | SecurityException ex) {
                            continue;
                        }
		}
		throw new IOException(JGitText.get().cannotCreateTempDir);
	}

	/**
	 * Expresses <code>other</code> as a relative file path from
	 * <code>base</code>. File-separator and case sensitivity are based on the
	 * current file system.
	 *
	 * See also
	 * {@link org.eclipse.jgit.util.FileUtils#relativizePath(String, String, String, boolean)}.
	 *
	 * @param base
	 *            Base path
	 * @param other
	 *            Destination path
	 * @return Relative path from <code>base</code> to <code>other</code>
	 * @since 4.8
	 */
	public static String relativizeNativePath(String base, String other) {
		return FS.DETECTED.relativize(base, other);
	}

	/**
	 * Expresses <code>other</code> as a relative file path from
	 * <code>base</code>. File-separator and case sensitivity are based on Git's
	 * internal representation of files (which matches Unix).
	 *
	 * See also
	 * {@link org.eclipse.jgit.util.FileUtils#relativizePath(String, String, String, boolean)}.
	 *
	 * @param base
	 *            Base path
	 * @param other
	 *            Destination path
	 * @return Relative path from <code>base</code> to <code>other</code>
	 * @since 4.8
	 */
	public static String relativizeGitPath(String base, String other) {
		return relativizePath(base, other, "/", false); //$NON-NLS-1$
	}


	/**
	 * Expresses <code>other</code> as a relative file path from <code>base</code>
	 * <p>
	 * For example, if called with the two following paths :
	 *
	 * <pre>
	 * <code>base = "c:\\Users\\jdoe\\eclipse\\git\\project"</code>
	 * <code>other = "c:\\Users\\jdoe\\eclipse\\git\\another_project\\pom.xml"</code>
	 * </pre>
	 *
	 * This will return "..\\another_project\\pom.xml".
	 *
	 * <p>
	 * <b>Note</b> that this will return the empty String if <code>base</code>
	 * and <code>other</code> are equal.
	 * </p>
	 *
	 * @param base
	 *            The path against which <code>other</code> should be
	 *            relativized. This will be assumed to denote the path to a
	 *            folder and not a file.
	 * @param other
	 *            The path that will be made relative to <code>base</code>.
	 * @param dirSeparator
	 *            A string that separates components of the path. In practice, this is "/" or "\\".
	 * @param caseSensitive
	 *            Whether to consider differently-cased directory names as distinct
	 * @return A relative path that, when resolved against <code>base</code>,
	 *         will yield the original <code>other</code>.
	 * @since 4.8
	 */
	public static String relativizePath(String base, String other, String dirSeparator, boolean caseSensitive) {
		if (base.equals(other))
			return ""; //$NON-NLS-1$

		final String[] baseSegments = base.split(Pattern.quote(dirSeparator));
		final String[] otherSegments = other.split(Pattern
				.quote(dirSeparator));

		int commonPrefix = 0;
		while (commonPrefix < baseSegments.length
				&& commonPrefix < otherSegments.length) {
			if (caseSensitive
					&& baseSegments[commonPrefix]
					.equals(otherSegments[commonPrefix]))
				commonPrefix++;
			else if (!caseSensitive
					&& baseSegments[commonPrefix]
							.equalsIgnoreCase(otherSegments[commonPrefix]))
				commonPrefix++;
			else
				break;
		}

		final StringBuilder builder = new StringBuilder();
		for (int i = commonPrefix; i < baseSegments.length; i++)
			builder.append("..").append(dirSeparator); //$NON-NLS-1$
		for (int i = commonPrefix; i < otherSegments.length; i++) {
			builder.append(otherSegments[i]);
			if (i < otherSegments.length - 1)
				builder.append(dirSeparator);
		}
		return builder.toString();
	}

	/**
	 * Determine if an IOException is a Stale NFS File Handle
	 *
	 * @param ioe
	 *            an {@link java.io.IOException} object.
	 * @return a boolean true if the IOException is a Stale NFS FIle Handle
	 * @since 4.1
	 */
	public static boolean isStaleFileHandle(IOException ioe) {
		String msg = ioe.getMessage();
		return msg != null
				&& msg.toLowerCase(Locale.ROOT)
						.matches("stale .*file .*handle"); //$NON-NLS-1$
	}

	/**
	 * Determine if a throwable or a cause in its causal chain is a Stale NFS
	 * File Handle
	 *
	 * @param throwable
	 *            a {@link java.lang.Throwable} object.
	 * @return a boolean true if the throwable or a cause in its causal chain is
	 *         a Stale NFS File Handle
	 * @since 4.7
	 */
	public static boolean isStaleFileHandleInCausalChain(Throwable throwable) {
		while (throwable != null) {
			if (throwable instanceof IOException
					&& isStaleFileHandle((IOException) throwable)) {
				return true;
			}
			throwable = throwable.getCause();
		}
		return false;
	}

	/**
         * @deprecated use {@link #isSymlink(Path)}
         * 
	 * @param file
	 * @return {@code true} if the passed file is a symbolic link
	 */
	static boolean isSymlink(File file) {
		return isSymlink(file != null ? file.toPath() : null);
	}

	/**
	 * @param file
	 * @return {@code true} if the passed file is a symbolic link
	 */
	static boolean isSymlink(Path file) {
		return Files.isSymbolicLink(file);
	}

	/**
         * @deprecated use {@link #lastModified(Path)}
         * 
	 * @param file
	 * @return lastModified attribute for given file, not following symbolic
	 *         links
	 * @throws IOException
	 */
	public static long lastModified(File file) throws IOException {
		return lastModified(toPath(file));
	}
        
	/**
	 * @param file
	 * @return lastModified attribute for given file, not following symbolic
	 *         links
	 * @throws IOException
	 */
	public static long lastModified(Path file) throws IOException {
		return Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS)
				.toMillis();
	}

	/**
         * @deprecated use {@link #setLastModified(Path, long)}
         * 
	 * @param file
	 * @param time
	 * @throws IOException
	 */
	static void setLastModified(File file, long time) throws IOException {
		setLastModified(toPath(file), time);
	}

	/**
	 * @param file
	 * @param time
	 * @throws IOException
	 */
	static void setLastModified(Path file, long time) throws IOException {
		Files.setLastModifiedTime(file, FileTime.fromMillis(time));
	}

        
	/**
         * @deprecated use {@link #exists(Path)}
         * 
	 * @param file
	 * @return {@code true} if the given file exists, not following symbolic
	 *         links
	 */
	static boolean exists(File file) {
		return exists(file != null ? file.toPath() : null);
	}

	/**
         * @deprecated use {@link #exists(Path)}
         * 
	 * @param file
	 * @return {@code true} if the given file exists, not following symbolic
	 *         links
	 */
	static boolean exists(Path file) {
		return Files.exists(file, LinkOption.NOFOLLOW_LINKS);
	}

	/**
         * @deprecated use {@link #isHidden(Path)}
         * 
	 * @param file
	 * @return {@code true} if the given file is hidden
	 * @throws IOException
	 */
	static boolean isHidden(File file) throws IOException {
		return Files.isHidden(toPath(file));
	}
        
	/**
	 * @param file
	 * @return {@code true} if the given file is hidden
	 * @throws IOException
	 */
	static boolean isHidden(Path file) throws IOException {
		return Files.isHidden(file);
	}

	/**
	 * @deprecated use {@link #setHidden(Path, boolean)}
	 *
	 * @param file
	 *            a {@link java.io.File} object.
	 * @param hidden
	 *            a boolean.
	 * @throws java.io.IOException
	 * @since 4.1
	 */
	public static void setHidden(File file, boolean hidden) throws IOException {
                setHidden(toPath(file), hidden);
	}

	/**
	 * Set a file hidden (on Windows)
	 *
	 * @param file
	 *            a {@link java.io.File} object.
	 * @param hidden
	 *            a boolean.
	 * @throws java.io.IOException
	 * @since 4.1
	 */
	public static void setHidden(Path file, boolean hidden) throws IOException {
                if (Files.isHidden(file) != hidden) {
                        DosFileAttributeView dos = Files.getFileAttributeView(file, 
                                DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                        if (dos != null) {
                            dos.setHidden(hidden); //$NON-NLS-1$
                        }
                }
	}

	/**
	 * Set 'executable' attribute
	 *
	 * @param file
	 *            a {@link java.nio.file.Path} object.
	 * @param executable
	 *            a boolean.
	 * @throws java.io.IOException
	 * 
	 */
        public static void setExecutable(Path file, boolean executable) throws IOException {        
                if (Files.isExecutable(file) != executable) {
                        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                        if (posix != null) {
                                PosixFileAttributes attr = posix.readAttributes();
                                Set<PosixFilePermission> permissions = attr.permissions();
                                if (executable) {
                                        permissions.add(PosixFilePermission.OWNER_EXECUTE);
                                } else {
                                        permissions.remove(PosixFilePermission.OWNER_EXECUTE);
                                        permissions.remove(PosixFilePermission.GROUP_EXECUTE);
                                        permissions.remove(PosixFilePermission.OTHERS_EXECUTE);
                                }
                                posix.setPermissions(permissions);
                        } else {
                                AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                                if (acl != null) {
                                        final UserPrincipal user = Files.getOwner(file);
                                        final List<AclEntry> list = acl.getAcl();
                                        if (executable) {
                                                final AclEntry entry = AclEntry.newBuilder()
                                                                .setType(AclEntryType.ALLOW)
                                                                .setPrincipal(user)
                                                                .setPermissions(AclEntryPermission.EXECUTE)
                                                                .build();
                                                list.add(entry);
                                        } else {
                                            for (int i = 0; i < list.size(); i++) {
                                                final AclEntry entry = list.get(i);
                                                if (AclEntryType.ALLOW == entry.type() &&
                                                        entry.permissions().contains(AclEntryPermission.EXECUTE)) {
                                                    list.set(i, AclEntry.newBuilder(entry).setType(AclEntryType.DENY).build());
                                                }
                                            }
                                        }
                                        acl.setAcl(list);
                                }
                        }
                }
        }
        
	/**
	 * Set 'readonly' attribute
	 *
	 * @param file
	 *            a {@link java.nio.file.Path} object.
	 * @param readonly
	 *            a boolean.
	 * @throws java.io.IOException
	 * 
	 */
        public static void setReadOnly(Path file, boolean readonly) throws IOException {
                if (Files.isWritable(file) == readonly) {
                        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                        if (posix != null) {
                                PosixFileAttributes attr = posix.readAttributes();
                                Set<PosixFilePermission> permissions = attr.permissions();
                                if (readonly) {
                                        permissions.remove(PosixFilePermission.OWNER_WRITE);
                                        permissions.remove(PosixFilePermission.GROUP_WRITE);
                                        permissions.remove(PosixFilePermission.OTHERS_WRITE);
                                } else {
                                        permissions.add(PosixFilePermission.OWNER_WRITE);
                                }
                                posix.setPermissions(permissions);

                        } else {
                                DosFileAttributeView dos = Files.getFileAttributeView(file, 
                                        DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                                if (dos != null) {
                                    dos.setReadOnly(readonly);
                                }
                        }
                }
        }

	/**
	 * @deprecated use {@link #getLength(Path)}
	 *
	 * @param file
	 *            a {@link java.io.File}.
	 * @return length of the given file
	 * @throws java.io.IOException
	 * @since 4.1
	 */
	public static long getLength(File file) throws IOException {
                return getLength(toPath(file));
	}
        
	/**
	 * Get file length
	 *
	 * @param file
	 *            a {@link java.io.File}.
	 * @return length of the given file
	 * @throws java.io.IOException
	 * @since 4.1
	 */
	public static long getLength(Path file) throws IOException {
		if (Files.isSymbolicLink(file))
			return Files.readSymbolicLink(file).toString()
					.getBytes(UTF_8).length;
		return Files.size(file);
	}

	/**
         * @deprecated use {@link #isDirectory(Path)}
         * 
	 * @param file
	 * @return {@code true} if the given file is a directory, not following
	 *         symbolic links
	 */
	static boolean isDirectory(File file) {
		return isDirectory(file.toPath());
	}

	/**
	 * @param file
	 * @return {@code true} if the given file is a directory, not following
	 *         symbolic links
	 */
	static boolean isDirectory(Path file) {
		return Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS);
	}

	/**
         * @deprecated use {@link #isFile(Path)}
         * 
	 * @param file
	 * @return {@code true} if the given file is a file, not following symbolic
	 *         links
	 */
	static boolean isFile(File file) {
		return isFile(file != null ? file.toPath() : null);
	}

	/**
	 * @param file
	 * @return {@code true} if the given file is a file, not following symbolic
	 *         links
	 */
	static boolean isFile(Path file) {
		return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
	}

	/**
	 * @deprecated use {@link #canExecute(Path)}
	 *
	 * @param file
	 *            a {@link java.io.File} object.
	 * @return {@code true} if the given file can be executed.
	 * @since 4.1
	 */
	public static boolean canExecute(File file) {
            return canExecute(file.toPath());
	}

	/**
	 * Whether the given file can be executed.
	 *
	 * @param file
	 *            a {@link java.io.File} object.
	 * @return {@code true} if the given file can be executed.
	 * @since 4.1
	 */
	public static boolean canExecute(Path file) {
		if (!Files.isRegularFile(file)) {
			return false;
		}
		return Files.isExecutable(file);
	}

	/**
	 * @param fs
	 * @param file
	 * @return non null attributes object
	 */
	static Attributes getFileAttributesBasic(FS fs, Path file) {
		try {
			BasicFileAttributes readAttributes = file
					.getFileSystem()
					.provider()
					.getFileAttributeView(file,
							BasicFileAttributeView.class,
							LinkOption.NOFOLLOW_LINKS).readAttributes();
			Attributes attributes = new Attributes(fs, file,
					true,
					readAttributes.isDirectory(),
					fs.supportsExecute() ? Files.isExecutable(file) : false,
					readAttributes.isSymbolicLink(),
					readAttributes.isRegularFile(), //
					readAttributes.creationTime().toMillis(), //
					readAttributes.lastModifiedTime().toMillis(),
					readAttributes.isSymbolicLink() ? Constants
							.encode(readSymLink(file)).length
							: readAttributes.size());
			return attributes;
		} catch (IOException e) {
			return new Attributes(file, fs);
		}
	}

	/**
	 * @deprecated use {@link #getFileAttributesPosix(FS, Path)}
	 *
	 * @param fs
	 *            a {@link org.eclipse.jgit.util.FS} object.
	 * @param file
	 *            a {@link java.io.File}.
	 * @return file system attributes for the given file.
	 * @since 4.1
	 */
	public static Attributes getFileAttributesPosix(FS fs, File file) {
                return getFileAttributesPosix(fs, file != null ? file.toPath() : null);
	}

	/**
	 * Get file system attributes for the given file.
	 *
	 * @param fs
	 *            a {@link org.eclipse.jgit.util.FS} object.
	 * @param file
	 *            a {@link java.io.File}.
	 * @return file system attributes for the given file.
	 * @since 4.1
	 */
	public static Attributes getFileAttributesPosix(FS fs, Path file) {
		try {
			PosixFileAttributes readAttributes = file
					.getFileSystem()
					.provider()
					.getFileAttributeView(file,
							PosixFileAttributeView.class,
							LinkOption.NOFOLLOW_LINKS).readAttributes();
			Attributes attributes = new Attributes(
					fs,
					file,
					true, //
					readAttributes.isDirectory(), //
					readAttributes.permissions().contains(
							PosixFilePermission.OWNER_EXECUTE),
					readAttributes.isSymbolicLink(),
					readAttributes.isRegularFile(), //
					readAttributes.creationTime().toMillis(), //
					readAttributes.lastModifiedTime().toMillis(),
					readAttributes.size());
			return attributes;
		} catch (IOException e) {
			return new Attributes(file, fs);
		}
	}

	/**
	 * @deprecated use {@link #normalize(Path)}
	 *
	 * @param file
	 *            a {@link java.io.File}.
	 * @return on Mac: NFC normalized {@link java.io.File}, otherwise the passed
	 *         file
	 * @since 4.1
	 */
	public static File normalize(File file) {
                return normalize(file != null ? file.toPath() : null).toFile();
	}
        
	/**
	 * NFC normalize a file (on Mac), otherwise do nothing
	 *
	 * @param file
	 *            a {@link java.io.File}.
	 * @return on Mac: NFC normalized {@link java.io.File}, otherwise the passed
	 *         file
	 * @since 4.1
	 */
	public static Path normalize(Path file) {
		if (SystemReader.getInstance().isMacOS()) {
			// TODO: Would it be faster to check with isNormalized first
			// assuming normalized paths are much more common
			String normalized = Normalizer.normalize(file.toString(),
					Normalizer.Form.NFC);
			return java.nio.file.Paths.get(normalized);
		}
		return file;
	}

	/**
	 * On Mac: get NFC normalized form of given name, otherwise the given name.
	 *
	 * @param name
	 *            a {@link java.lang.String} object.
	 * @return on Mac: NFC normalized form of given name
	 * @since 4.1
	 */
	public static String normalize(String name) {
		if (SystemReader.getInstance().isMacOS()) {
			if (name == null)
				return null;
			return Normalizer.normalize(name, Normalizer.Form.NFC);
		}
		return name;
	}

	/**
	 * Best-effort variation of {@link java.io.File#getCanonicalFile()}
	 * returning the input file if the file cannot be canonicalized instead of
	 * throwing {@link java.io.IOException}.
	 *
	 * @param file
	 *            to be canonicalized; may be {@code null}
	 * @return canonicalized file, or the unchanged input file if
	 *         canonicalization failed or if {@code file == null}
	 * @throws java.lang.SecurityException
	 *             if {@link java.io.File#getCanonicalFile()} throws one
	 * @since 4.2
	 */
	public static File canonicalize(File file) {
		if (file == null) {
			return null;
		}
		try {
			return file.getCanonicalFile();
		} catch (IOException e) {
			return file;
		}
	}

	/**
	 * Convert a path to String, replacing separators as necessary.
	 *
	 * @param file
	 *            a {@link java.io.File}.
	 * @return file's path as a String
	 * @since 4.10
	 */
	public static String pathToString(File file) {
		final String path = file.getPath();
		if (SystemReader.getInstance().isWindows()) {
			return path.replace('\\', '/');
		}
		return path;
	}
}
