package io.github.instreest.jkite.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Functional tests for the Windows launcher (jkite.cmd) using a fake jkite.jar:
 * exit codes and output must pass through unchanged. jkite.cmd is
 * self-contained, so there is no PowerShell launcher to hand over to.
 */
@EnabledOnOs(OS.WINDOWS)
class TestWindowsLaunchers extends AbstractScriptTest {

	private Path binDir;

	@BeforeEach
	void setupLaunchers() throws IOException {
		binDir = Files.createDirectories(tempDir.resolve("bin"));
		Files.copy(CMD_SCRIPT, binDir.resolve("jkite.cmd"));
		Files.copy(CMD_SCRIPT.resolveSibling("jkite-bootstrap-jdk.cmd"), binDir.resolve("jkite-bootstrap-jdk.cmd"));
		// the JDK the bootstrap script would install, pinned at an address that
		// nothing answers, so a test that reaches it fails fast instead of
		// fetching 200 MB
		Files.write(binDir.resolve("jkite.properties"),
				("bootstrapJdkVersion=99.0.0\n"
						+ "bootstrapJdkUrl." + indexPlatform() + "=https://127.0.0.1:1/nowhere/jdk.zip\n"
						+ "bootstrapJdkSha256Sum." + indexPlatform() + "=00\n")
					.getBytes(StandardCharsets.UTF_8));
		createFakeJar(binDir.resolve("jkite.jar"));
	}

	@Test
	void cmdPropagatesExitCodeAndOutput() throws Exception {
		RunResult result = runLauncher(cmdLauncher(), "exit", "3");
		assertEquals(3, result.exitCode, result.stderr);
		assertTrue(result.stdout.contains("some output"), result.stdout);
		assertTrue(result.stderr.contains("some error output"), result.stderr);
	}




	@Test
	void cmdIgnoresOldJavaHome() throws Exception {
		// JAVA_HOME is rejected and the javac on the PATH is taken instead, so
		// the jar still runs: the exit code is the one the jar was asked for.
		RunResult result = runLauncher(cmdLauncher(), "JAVA_HOME", createFakeJdk("1.8.0_292"), "exit", "3");
		assertEquals(3, result.exitCode, result.stderr);
		assertTrue(result.stderr.contains("older than Java 11"), result.stderr);
	}

	@Test
	void cmdDownloadsAJdkWhenNothingOnTheMachineWillDo() throws Exception {
		// Nothing usable anywhere: JAVA_HOME too old and a PATH without javac,
		// so jkite.cmd has jkite-bootstrap-jdk.cmd install the JDK
		// jkite.properties pins, which is pinned at an address nothing
		// answers so that it fails fast instead of fetching 200 MB.
		RunResult result = runLauncher(cmdLauncher(), "JAVA_HOME", createFakeJdk("1.8.0_292"),
				"PATH", System.getenv("SystemRoot") + "\\System32",
				"JKITE_DOWNLOAD_RETRY", "0", "exit", "3");
		assertTrue(result.exitCode != 0, result.stderr);
		assertTrue(result.stderr.contains("older than Java 11"), result.stderr);
		assertTrue(result.stderr.contains("Error downloading the JDK"), result.stderr);
	}

	@Test
	void cmdPrefersBootstrapJdkOverJavaHome() throws Exception {
		linkCachedJdk();
		RunResult result = runLauncher(cmdLauncher(), "JAVA_HOME", createFakeJdk("1.8.0_292"), "exit", "3");
		assertEquals(3, result.exitCode, result.stderr);
		assertFalse(result.stderr.contains("JAVA_HOME"), result.stderr);
	}


	/**
	 * What is on the PATH as javac is usually not the JDK's own. The Oracle
	 * javapath stub, the App Execution alias and the wrapper scripts some
	 * installers lay down all sit in a directory that is not a JDK, so the home
	 * cannot be read off the path javac was found at - javac is asked instead,
	 * and this is the case that answer exists for.
	 *
	 * The bash half is TestLaunchers.theJavacOnThePathIsAskedWhereItsHomeIs.
	 */
	@Test
	void cmdAsksTheJavacOnThePathWhereItsHomeIs() throws Exception {
		Path shims = Files.createDirectories(tempDir.resolve("shims"));
		Files.write(shims.resolve("javac.cmd"),
				("@echo off\r\n\"" + System.getProperty("java.home") + "\\bin\\javac.exe\" %*\r\n")
					.getBytes(StandardCharsets.UTF_8));

		// JAVA_HOME is turned down, so the search goes on to the PATH, where the
		// only javac is the shim and its directory holds nothing else
		RunResult result = runLauncher(cmdLauncher(),
				"JAVA_HOME", createFakeJdk("1.8.0_292"),
				"PATH", shims + File.pathSeparator + System.getenv("SystemRoot") + "\\System32",
				"exit", "3");

		assertEquals(3, result.exitCode, result.stderr);
	}

	@Test
	void cmdIgnoresJavaHomeOfUnknownVersion() throws Exception {
		// A JAVA_HOME without a 'release' file: its version cannot be read, so
		// it is ignored and the javac on the PATH runs the jar.
		RunResult result = runLauncher(cmdLauncher(), "JAVA_HOME", createFakeJdk(null), "exit", "3");
		assertEquals(3, result.exitCode, result.stderr);
		assertTrue(result.stderr.contains("could not be determined"), result.stderr);
	}




	/**
	 * Makes the running JDK available as JKITE_CACHE_DIR\jdks\bootstrap (as if
	 * the launcher had downloaded it) so the launchers have a JDK to fall back
	 * to when JAVA_HOME is rejected, without downloading one.
	 */
	private void linkCachedJdk() throws Exception {
		Path jdks = Files.createDirectories(tempDir.resolve("cache/jdks"));
		link(jdks.resolve("bootstrap"), Paths.get(System.getProperty("java.home")));
	}

	private void link(Path link, Path target) throws Exception {
		RunResult result = runProcess(
				Arrays.asList("cmd.exe", "/c", "mklink", "/j", link.toString(), target.toString()), System.getenv());
		assertEquals(0, result.exitCode, result.stderr);
	}


	private List<String> cmdLauncher() {
		return new ArrayList<>(Arrays.asList("cmd.exe", "/c", binDir.resolve("jkite.cmd").toString()));
	}


	private RunResult runLauncher(List<String> command, String... args) throws Exception {
		Map<String, String> env = new HashMap<>(System.getenv());
		// the defaults come first, so that a test naming one of them overrides it
		// rather than being overridden by it
		env.put("JAVA_HOME", System.getProperty("java.home"));
		env.put("JKITE_DIR", tempDir.resolve("jbang-home").toString());
		env.put("JKITE_CACHE_DIR", tempDir.resolve("cache").toString());
		int i = 0;
		// leading "NAME", "value" pairs are environment variables
		while (args.length - i > 2
				&& (args[i].startsWith("JKITE_") || args[i].equals("JAVA_HOME") || args[i].equals("PATH"))) {
			env.put(args[i], args[i + 1]);
			i += 2;
		}
		command.addAll(Arrays.asList(args).subList(i, args.length));
		return runProcess(command, env);
	}

	/**
	 * Creates a directory that looks like a JDK of the given version (no
	 * 'release' file when null) but whose java.exe would fail: the launchers must
	 * not pick it.
	 */
	private String createFakeJdk(String version) throws IOException {
		Path jdk = Files.createDirectories(tempDir.resolve("oldjdk"));
		Files.createDirectories(jdk.resolve("bin"));
		Files.write(jdk.resolve("bin/javac.exe"), new byte[0]);
		Files.write(jdk.resolve("bin/java.exe"), new byte[0]);
		if (version != null) {
			Files.write(jdk.resolve("release"),
					Arrays.asList("JAVA_VERSION=\"" + version + "\"", "OS_NAME=\"Windows\""), StandardCharsets.UTF_8);
		}
		return jdk.toString();
	}
}
