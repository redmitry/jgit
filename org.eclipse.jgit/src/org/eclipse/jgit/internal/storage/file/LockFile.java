/*
 * Copyright (C) 2007, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006-2008, Shawn O. Pearce <spearce@spearce.org>
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

import java.io.File;
import static org.eclipse.jgit.lib.Constants.LOCK_SUFFIX;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.text.MessageFormat;
import java.time.Instant;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS.LockToken;
import org.eclipse.jgit.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Git style file locking and replacement.
 * <p>
 * To modify a ref file Git tries to use an atomic update approach: we write the
 * new data into a brand new file, then rename it in place over the old name.
 * This way we can just delete the temporary file if anything goes wrong, and
 * nothing has been damaged. To coordinate access from multiple processes at
 * once Git tries to atomically create the new temporary file under a well-known
 * name.
 */
public class LockFile {
	private final static Logger LOG = LoggerFactory.getLogger(LockFile.class);

	/**
	 * @deprecated use {@link #unlock(Path)}
	 *
	 * @param file
	 *            a {@link java.io.File} object.
	 * @return true if unlocked, false if unlocking failed
	 */
	public static boolean unlock(File file) {
            return unlock(file != null ? file.toPath() : null);
	}

	/**
	 * Unlock the given file.
	 * <p>
	 * This method can be used for recovering from a thrown
	 * {@link org.eclipse.jgit.errors.LockFailedException} . This method does
	 * not validate that the lock is or is not currently held before attempting
	 * to unlock it.
	 *</p>
         * 
	 * @param file
	 *            a {@link java.io.File} object.
	 * @return true if unlocked, false if unlocking failed
	 */
	public static boolean unlock(Path file) {
		final Path lockFile = getLockFile(file);
                final int flags = FileUtils.RETRY | FileUtils.SKIP_MISSING;
                
		try {
                    FileUtils.delete(lockFile, flags);
		} catch (IOException ignored) {
			// Ignore and return whether lock file still exists
		}
		return !Files.exists(lockFile);
	}

	/**
	 * Get the lock file corresponding to the given file.
	 *
	 * @param file
	 * @return lock file
	 */

	static Path getLockFile(Path file) {
            return file.resolveSibling(file.getFileName().toString() + LOCK_SUFFIX);
	}

	private final Path ref;

	private final Path lck;

	private boolean haveLck;

	SeekableByteChannel os;

	private boolean needSnapshot;

	boolean fsync;

	private FileSnapshot commitSnapshot;

	private LockToken token;

	/**
	 * @deprecated use {@link #LockFile(Path)}
	 *
	 * @param f
	 *            the file that will be locked.
	 */
	public LockFile(File f) {
                this(f != null ? f.toPath() : null);
	}

	/**
	 * Create a new lock for any file.
	 *
	 * @param f
	 *            the file that will be locked.
	 */
	public LockFile(Path f) {
		ref = f;
		lck = getLockFile(ref);
	}
        
	/**
	 * Try to establish the lock.
	 *
	 * @return true if the lock is now held by the caller; false if it is held
	 *         by someone else.
	 * @throws java.io.IOException
	 *             the temporary output file could not be created. The caller
	 *             does not hold the lock.
	 */
	public boolean lock() throws IOException {
                FileUtils.mkdirs(lck.getParent(), true);
		token = FS.DETECTED.createNewFileAtomic(lck);
		if (token.isCreated()) {
			haveLck = true;
			try {
				os = Files.newByteChannel(lck, 
                                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, 
                                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
			} catch (IOException ioe) {
				unlock();
				throw ioe;
			}
		} else {
			closeToken();
		}
		return haveLck;
	}

	/**
	 * Try to establish the lock for appending.
	 *
	 * @return true if the lock is now held by the caller; false if it is held
	 *         by someone else.
	 * @throws java.io.IOException
	 *             the temporary output file could not be created. The caller
	 *             does not hold the lock.
	 */
	public boolean lockForAppend() throws IOException {
		if (!lock())
			return false;
		copyCurrentContent();
		return true;
	}

	/**
	 * Copy the current file content into the temporary file.
	 * <p>
	 * This method saves the current file content by inserting it into the
	 * temporary file, so that the caller can safely append rather than replace
	 * the primary file.
	 * <p>
	 * This method does nothing if the current file does not exist, or exists
	 * but is empty.
	 *
	 * @throws java.io.IOException
	 *             the temporary file could not be written, or a read error
	 *             occurred while reading from the current file. The lock is
	 *             released before throwing the underlying IO exception to the
	 *             caller.
	 * @throws java.lang.RuntimeException
	 *             the temporary file could not be written. The lock is released
	 *             before throwing the underlying exception to the caller.
	 */
	public void copyCurrentContent() throws IOException {
		requireLock();
		try (OutputStream out = Channels.newOutputStream(os)) {
                        Files.copy(ref, out);
		} catch (NoSuchFileException fnfe) {
			if (Files.exists(ref)) {
				unlock();
				throw fnfe;
			}
			// Don't worry about a file that doesn't exist yet, it
			// conceptually has no current content to copy.
			//
		} catch (IOException | RuntimeException | Error ioe) {
			unlock();
			throw ioe;
		}
	}

	/**
	 * Write an ObjectId and LF to the temporary file.
	 *
	 * @param id
	 *            the id to store in the file. The id will be written in hex,
	 *            followed by a sole LF.
	 * @throws java.io.IOException
	 *             the temporary file could not be written. The lock is released
	 *             before throwing the underlying IO exception to the caller.
	 * @throws java.lang.RuntimeException
	 *             the temporary file could not be written. The lock is released
	 *             before throwing the underlying exception to the caller.
	 */
	public void write(ObjectId id) throws IOException {
		byte[] buf = new byte[Constants.OBJECT_ID_STRING_LENGTH + 1];
		id.copyTo(buf, 0);
		buf[Constants.OBJECT_ID_STRING_LENGTH] = '\n';
		write(buf);
	}

	/**
	 * Write arbitrary data to the temporary file.
	 *
	 * @param content
	 *            the bytes to store in the temporary file. No additional bytes
	 *            are added, so if the file must end with an LF it must appear
	 *            at the end of the byte array.
	 * @throws java.io.IOException
	 *             the temporary file could not be written. The lock is released
	 *             before throwing the underlying IO exception to the caller.
	 * @throws java.lang.RuntimeException
	 *             the temporary file could not be written. The lock is released
	 *             before throwing the underlying exception to the caller.
	 */
	public void write(byte[] content) throws IOException {
		requireLock();
		try {
                        ByteBuffer buf = ByteBuffer.wrap(content);
                        while (buf.hasRemaining()) {
                                os.write(buf);
                        }
			os.close();
			os = null;
		} catch (IOException ioe) {
			unlock();
			throw ioe;
		} catch (RuntimeException ioe) {
			unlock();
			throw ioe;
		} catch (Error ioe) {
			unlock();
			throw ioe;
		}
	}

	/**
	 * Obtain the direct output stream for this lock.
	 * <p>
	 * The stream may only be accessed once, and only after {@link #lock()} has
	 * been successfully invoked and returned true. Callers must close the
	 * stream prior to calling {@link #commit()} to commit the change.
	 *
	 * @return a stream to write to the new file. The stream is unbuffered.
	 */
	public OutputStream getOutputStream() {
		requireLock();

		final OutputStream out = Channels.newOutputStream(os);

		return new OutputStream() {
			@Override
			public void write(byte[] b, int o, int n)
					throws IOException {
				out.write(b, o, n);
			}

			@Override
			public void write(byte[] b) throws IOException {
				out.write(b);
			}

			@Override
			public void write(int b) throws IOException {
				out.write(b);
			}

			@Override
			public void close() throws IOException {
				try {
					out.close();
					os = null;
				} catch (IOException ioe) {
					unlock();
					throw ioe;
				} catch (RuntimeException ioe) {
					unlock();
					throw ioe;
				} catch (Error ioe) {
					unlock();
					throw ioe;
				}
			}
		};
	}

	void requireLock() {
		if (os == null) {
			unlock();
			throw new IllegalStateException(MessageFormat.format(JGitText.get().lockOnNotHeld, ref));
		}
	}

	/**
	 * Request that {@link #commit()} remember modification time.
	 * <p>
	 * This is an alias for {@code setNeedSnapshot(true)}.
	 *
	 * @param on
	 *            true if the commit method must remember the modification time.
	 */
	public void setNeedStatInformation(boolean on) {
		setNeedSnapshot(on);
	}

	/**
	 * Request that {@link #commit()} remember the
	 * {@link org.eclipse.jgit.internal.storage.file.FileSnapshot}.
	 *
	 * @param on
	 *            true if the commit method must remember the FileSnapshot.
	 */
	public void setNeedSnapshot(boolean on) {
		needSnapshot = on;
	}

	/**
	 * Request that {@link #commit()} force dirty data to the drive.
	 *
	 * @param on
	 *            true if dirty data should be forced to the drive.
	 */
	public void setFSync(boolean on) {
		fsync = on;
	}

	/**
	 * Wait until the lock file information differs from the old file.
	 * <p>
	 * This method tests the last modification date. If both are the same, this
	 * method sleeps until it can force the new lock file's modification date to
	 * be later than the target file.
	 *
	 * @throws java.lang.InterruptedException
	 *             the thread was interrupted before the last modified date of
	 *             the lock file was different from the last modified date of
	 *             the target file.
	 */
	public void waitForStatChange() throws InterruptedException {
		FileSnapshot o = FileSnapshot.save(ref);
		FileSnapshot n = FileSnapshot.save(lck);
		while (o.equals(n)) {
			Thread.sleep(25 /* milliseconds */);
                        try {
                            Files.setLastModifiedTime(lck, FileTime.from(Instant.now()));
                            n = FileSnapshot.save(lck);
                        } catch(IOException ex) {
                            
                        }
		}
	}

	/**
	 * Commit this change and release the lock.
	 * <p>
	 * If this method fails (returns false) the lock is still released.
	 *
	 * @return true if the commit was successful and the file contains the new
	 *         data; false if the commit failed and the file remains with the
	 *         old data.
	 * @throws java.lang.IllegalStateException
	 *             the lock is not held.
	 */
	public boolean commit() {
		if (os != null) {
			unlock();
			throw new IllegalStateException(MessageFormat.format(JGitText.get().lockOnNotClosed, ref));
		}

		saveStatInformation();
		try {
			FileUtils.rename(lck, ref, StandardCopyOption.ATOMIC_MOVE);
			haveLck = false;
			closeToken();
			return true;
		} catch (IOException e) {
			unlock();
			return false;
		}
	}

	private void closeToken() {
		if (token != null) {
			token.close();
			token = null;
		}
	}

	private void saveStatInformation() {
		if (needSnapshot)
			commitSnapshot = FileSnapshot.save(lck);
	}

	/**
	 * Get the modification time of the output file when it was committed.
	 *
	 * @return modification time of the lock file right before we committed it.
	 */
	public long getCommitLastModified() {
		return commitSnapshot.lastModified();
	}

	/**
	 * Get the {@link FileSnapshot} just before commit.
	 *
	 * @return get the {@link FileSnapshot} just before commit.
	 */
	public FileSnapshot getCommitSnapshot() {
		return commitSnapshot;
	}

	/**
	 * Update the commit snapshot {@link #getCommitSnapshot()} before commit.
	 * <p>
	 * This may be necessary if you need time stamp before commit occurs, e.g
	 * while writing the index.
	 */
	public void createCommitSnapshot() {
		saveStatInformation();
	}

	/**
	 * Unlock this file and abort this change.
	 * <p>
	 * The temporary file (if created) is deleted before returning.
	 */
	public void unlock() {
		if (os != null) {
			try {
				os.close();
			} catch (IOException e) {
				LOG.error(MessageFormat
						.format(JGitText.get().unlockLockFileFailed, lck), e);
			}
			os = null;
		}

		if (haveLck) {
			haveLck = false;
			try {
                                FileUtils.delete(lck, FileUtils.RETRY);
			} catch (IOException e) {
				LOG.error(MessageFormat
						.format(JGitText.get().unlockLockFileFailed, lck), e);
			} finally {
				closeToken();
			}
		}
	}

	/** {@inheritDoc} */
	@SuppressWarnings("nls")
	@Override
	public String toString() {
		return "LockFile[" + lck + ", haveLck=" + haveLck + "]";
	}
}
