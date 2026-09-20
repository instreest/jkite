package io.github.instreest.jkite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

/**
 * Where jkite keeps things when JKITE_DIR says nothing.
 *
 * This has to be the directory the launcher already chose. The launcher runs
 * before any JVM exists and reads it out of the environment - $HOME on POSIX,
 * %USERPROFILE% on Windows - while Java's user.home on Linux comes from the
 * passwd entry instead. Anything that sets HOME without editing /etc/passwd
 * makes those two differ, and a container image, a systemd unit and
 * "sudo -u" all do.
 *
 * The paths here are built for the platform the test runs on. "/home/app" is
 * not absolute on Windows - it has no drive - so a Unix spelling would make
 * this assert the fallback rather than the thing it is about. What the
 * launcher actually hands over there is %USERPROFILE%, which always has one.
 */
class TestHomeDir {

	private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
	private static final String FROM_ENV = WINDOWS ? "C:\\Users\\app" : "/home/app";
	private static final String USER_HOME = WINDOWS ? "C:\\Users\\real" : "/home/real";
	private static final String NOWHERE = WINDOWS ? "C:\\nonexistent" : "/nonexistent";

	@Test
	void theEnvironmentIsPreferredOverUserHome() {
		assertEquals(Paths.get(FROM_ENV), Settings.homeDir(FROM_ENV, NOWHERE));
	}

	@Test
	void userHomeIsUsedWhenTheEnvironmentSaysNothing() {
		assertEquals(Paths.get(USER_HOME), Settings.homeDir(null, USER_HOME));
		assertEquals(Paths.get(USER_HOME), Settings.homeDir("", USER_HOME));
		assertEquals(Paths.get(USER_HOME), Settings.homeDir("   ", USER_HOME));
	}

	/**
	 * A relative HOME would put the cache wherever the run happened to start,
	 * so every directory would get one. It is not what the launcher did with
	 * it either, which is the thing this is trying to agree with.
	 */
	@Test
	void aRelativeHomeIsNotUsed() {
		assertEquals(Paths.get(USER_HOME), Settings.homeDir("relative/path", USER_HOME));
	}
}
