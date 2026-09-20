package io.github.instreest.jkite.util;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import io.github.instreest.jkite.Settings;
import dev.jbang.ExitException;
import dev.jbang.util.Util;

/**
 * An advisory lock on one thing in the cache, held while it is written.
 *
 * Several jkite runs share <code>$JKITE_DIR</code> - a build matrix, a
 * multi-module build, two terminals - and a cache entry that several of them
 * write at once is a cache entry one of them loses. Taking a lock first makes
 * them take turns instead.
 *
 * A lock that cannot be taken is not an error: not every filesystem supports
 * locking (some network mounts do not), and the work is still worth doing
 * without one. {@link #isHeld()} says which happened, so a caller for whom that
 * matters can say so or take another precaution.
 *
 * It holds between processes, which is what shares a cache directory. Asking
 * for a lock that this same JVM already holds is one of the cases that cannot
 * be taken, and it goes on unlocked like any other.
 *
 * The wait for whoever holds it is bounded. An unbounded one has no upper cost
 * and no diagnosis: the run prints that it is waiting and then says nothing
 * ever again, and on a CI runner it takes the job's whole budget with it and
 * leaves no log to read afterwards. JKITE_LOCK_TIMEOUT bounds it, ten minutes
 * by default, or 0 to wait for as long as it takes. That is the same variable
 * and the same default as the lock the bootstrap scripts take before there is
 * a JVM to run this in - it was honoured there and ignored here, which made it
 * a setting that worked for half of a run.
 */
public final class CacheLock implements AutoCloseable {
	private final Path file;
	private final RandomAccessFile raf;
	private final FileLock lock;

	private CacheLock(Path file, RandomAccessFile raf, FileLock lock) {
		this.file = file;
		this.raf = raf;
		this.lock = lock;
	}

	/** How often the lock is asked for again while waiting. */
	private static final long POLL_MILLIS = 200;

	/**
	 * Takes the lock named <code>name</code>, waiting for whoever holds it.
	 *
	 * @param waitMessage printed once when there is someone to wait for, or
	 *                    null to wait quietly
	 */
	public static CacheLock acquire(String name, String waitMessage) {
		Path file = Settings.getLockDir().resolve(name + ".lock");
		return acquireAt(file, waitMessage);
	}

	/** Takes the lock in the file itself, for a lock outside the lock directory. */
	public static CacheLock acquireAt(Path file, String waitMessage) {
		return acquireAt(file, waitMessage, Settings.getLockTimeout());
	}

	/** The same, with the wait bounded explicitly rather than by the setting. */
	static CacheLock acquireAt(Path file, String waitMessage, int timeoutSeconds) {
		RandomAccessFile raf = null;
		try {
			Files.createDirectories(file.toAbsolutePath().getParent());
			raf = new RandomAccessFile(file.toFile(), "rw");
			FileChannel channel = raf.getChannel();
			FileLock lock = waitFor(channel, file, waitMessage, timeoutSeconds);
			return new CacheLock(file, raf, lock);
		} catch (ExitException e) {
			// giving up on a lock somebody else holds, which is not the same as
			// not being able to lock: it does not fall through to the unlocked
			// path below. The file is still ours to let go of.
			closeQuietly(raf);
			throw e;
		} catch (IOException | RuntimeException e) {
			Util.verboseMsg("Could not lock " + file + ", continuing without a lock: " + e);
			// the file was opened before the lock was asked for, and going on
			// without the lock is no reason to go on holding it open: on Windows
			// that keeps the file itself locked for as long as the run lasts
			closeQuietly(raf);
			return new CacheLock(file, null, null);
		}
	}

	/**
	 * Asks for the lock until it is given or the timeout runs out.
	 *
	 * FileChannel.lock() would do the waiting itself and in one call, but it
	 * waits for as long as the holder takes, and there is no interrupting it
	 * with a deadline. Asking again on a timer costs one syscall every fifth of
	 * a second and can stop.
	 */
	private static FileLock waitFor(FileChannel channel, Path file, String waitMessage, int timeout)
			throws IOException {
		FileLock lock = channel.tryLock();
		if (lock != null) {
			return lock;
		}
		if (waitMessage != null) {
			Util.infoMsg(waitMessage);
		}
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(timeout, 0));
		while (timeout <= 0 || System.nanoTime() < deadline) {
			try {
				Thread.sleep(POLL_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
						"Interrupted while waiting for the lock on " + file);
			}
			lock = channel.tryLock();
			if (lock != null) {
				return lock;
			}
		}
		// Going on without the lock is not the answer here. Not being able to
		// lock at all is one thing - the work is still worth doing - but this
		// is somebody holding it, and writing the entry anyway is the race the
		// lock exists to stop.
		throw new ExitException(ExitException.EXIT_UNEXPECTED_STATE,
				"Gave up after " + timeout + "s waiting for the lock on " + file
						+ ". Another jkite process is holding it, or one was killed in a way that"
						+ " left it held. Check for a running jkite; if there is none, delete that"
						+ " file. Set " + Settings.ENV_LOCK_TIMEOUT + " to wait longer, or to 0 to"
						+ " wait for as long as it takes.");
	}

	private static void closeQuietly(RandomAccessFile raf) {
		if (raf != null) {
			try {
				raf.close();
			} catch (IOException e) {
				// nothing left to do about it
			}
		}
	}

	/** False when the lock could not be taken and the work goes on unprotected. */
	public boolean isHeld() {
		return lock != null;
	}

	@Override
	public void close() {
		try {
			if (lock != null) {
				lock.release();
			}
			if (raf != null) {
				raf.close();
			}
		} catch (IOException e) {
			Util.verboseMsg("Could not release the lock on " + file + ": " + e);
		}
	}
}
