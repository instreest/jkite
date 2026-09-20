package io.github.instreest.jkite.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jbang.ExitException;

/**
 * The lock that keeps two jkite runs from writing the same cache entry at
 * once. What it protects against is another process; a filesystem that cannot
 * lock is not a reason to stop.
 */
class TestCacheLock {

	@TempDir
	Path dir;

	@Test
	void aLockIsTakenAndItsFileIsCreated() {
		Path file = dir.resolve("deep/down/thing.lock");

		try (CacheLock lock = CacheLock.acquireAt(file, null)) {
			assertTrue(lock.isHeld(), "the lock was not taken");
			assertTrue(Files.isRegularFile(file), "the lock file was not created");
		}
	}

	@Test
	void theLockIsFreeAgainAfterItIsClosed() {
		Path file = dir.resolve("thing.lock");

		try (CacheLock first = CacheLock.acquireAt(file, null)) {
			assertTrue(first.isHeld());
		}

		try (CacheLock second = CacheLock.acquireAt(file, null)) {
			assertTrue(second.isHeld(), "the lock was not released");
		}
	}

	/**
	 * The lock is between processes, which is what shares a cache directory. A
	 * second one inside the same JVM cannot be taken, and rather than fail the
	 * run it goes on unlocked - the same answer a filesystem that cannot lock
	 * gets.
	 */
	@Test
	void aSecondLockInTheSameRunGoesOnWithoutOne() {
		Path file = dir.resolve("thing.lock");

		try (CacheLock held = CacheLock.acquireAt(file, null);
				CacheLock second = CacheLock.acquireAt(file, null)) {
			assertTrue(held.isHeld());
			assertFalse(second.isHeld(), "two locks on one file were reported as both held");
		}
	}

	/** A lock that cannot be taken at all is not an error, only unlocked work. */
	@Test
	void aPlaceThatCannotHoldALockIsNotAnError() throws IOException {
		// a directory cannot be made underneath a file, on any of the systems
		// this runs on
		Path file = dir.resolve("not-a-directory");
		Files.write(file, "x".getBytes(StandardCharsets.UTF_8));

		try (CacheLock lock = CacheLock.acquireAt(file.resolve("thing.lock"), null)) {
			assertFalse(lock.isHeld(), "a lock was reported where none could be taken");
		}
	}

	@Test
	void closingTwiceIsHarmless() {
		CacheLock lock = CacheLock.acquireAt(dir.resolve("thing.lock"), null);
		lock.close();
		lock.close();
	}

	// -------------------------------------------------------------------------
	// a lock held by somebody else
	// -------------------------------------------------------------------------

	private Process holder;

	/**
	 * Waits, rather than only asking. destroyForcibly() returns as soon as
	 * the kill is sent, and on Windows the holder still has the lock file
	 * open until it has actually gone - which is after JUnit has tried to
	 * delete the temp directory, so the test fails on cleanup having passed.
	 * POSIX unlinks an open file happily, which is why this only ever showed
	 * up on the Windows job.
	 */
	@AfterEach
	void stopTheHolder() throws InterruptedException {
		if (holder != null) {
			holder.destroyForcibly();
			assertTrue(holder.waitFor(30, TimeUnit.SECONDS),
					"the holder would not die, so the lock file is still open");
		}
	}

	/**
	 * The wait used to be FileChannel.lock(), which waits for as long as the
	 * holder takes. A holder that is never going to let go - killed in a way
	 * that left the lock held, or stuck itself - turned every later run into a
	 * process that printed one line and then did nothing, forever. On a CI
	 * runner that is the job's whole budget spent and no log to read after.
	 */
	@Test
	void aLockSomebodyElseHoldsIsGivenUpOnRatherThanWaitedOutForever() throws Exception {
		Path file = dir.resolve("held.lock");
		startHolder(file);

		// preemptively: the thing being tested is that this returns at all, so
		// a failure here has to be the test ending rather than the test waiting
		ExitException e = assertTimeoutPreemptively(Duration.ofSeconds(30),
				() -> assertThrows(ExitException.class, () -> CacheLock.acquireAt(file, null, 1)),
				"a 1 second timeout was still waiting after 30");

		assertTrue(e.getMessage().contains(file.toString()), e.getMessage());
		assertTrue(e.getMessage().contains("JKITE_LOCK_TIMEOUT"), e.getMessage());
	}

	/** And the bound is a bound on waiting, not a refusal to wait at all. */
	@Test
	void aLockIsTakenOnceTheOtherProcessLetsGoOfIt() throws Exception {
		Path file = dir.resolve("handed-over.lock");
		startHolder(file);

		new Thread(() -> {
			try {
				Thread.sleep(500);
				holder.destroy();
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		}).start();

		try (CacheLock lock = CacheLock.acquireAt(file, null, 60)) {
			assertTrue(lock.isHeld(), "the lock was never taken, though the holder let go of it");
		}
	}

	/**
	 * Another process, because that is the only thing this lock is about: a
	 * second lock inside one JVM is refused by the JVM itself and never
	 * reaches the wait. It is launched from source rather than compiled, which
	 * java has been able to do since 11, so there is nothing to build here.
	 */
	private void startHolder(Path file) throws Exception {
		Path source = dir.resolve("Holder.java");
		Files.write(source, ("import java.io.RandomAccessFile;\n"
				+ "public class Holder {\n"
				+ "  public static void main(String[] a) throws Exception {\n"
				+ "    RandomAccessFile f = new RandomAccessFile(a[0], \"rw\");\n"
				+ "    f.getChannel().lock();\n"
				+ "    System.out.println(\"locked\");\n"
				+ "    Thread.sleep(600000);\n"
				+ "  }\n"
				+ "}\n").getBytes(StandardCharsets.UTF_8));
		Files.createDirectories(file.toAbsolutePath().getParent());

		String java = ProcessHandle.current().info().command().orElseThrow(IllegalStateException::new);
		holder = new ProcessBuilder(java, source.toString(), file.toString())
			.redirectErrorStream(true)
			.start();
		// Wait for it to say it has the lock, or the test races it. Read on
		// past whatever the JVM says for itself first - JAVA_TOOL_OPTIONS
		// prints a line before main() runs, and a CI machine may set it.
		java.io.BufferedReader out = new java.io.BufferedReader(
				new java.io.InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8));
		StringBuilder seen = new StringBuilder();
		for (String line = out.readLine(); line != null; line = out.readLine()) {
			if ("locked".equals(line)) {
				return;
			}
			seen.append(line).append('\n');
		}
		fail("the holder never took the lock. It said:\n" + seen);
	}
}
