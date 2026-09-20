package io.github.instreest.jkite.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The launcher fetches the jar and a JDK before any JVM exists, so the gate in
 * the jar cannot cover those two. It asks about them itself, under the same
 * contract the jar uses: JKITE_CONFIRM_DOWNLOADS is auto, always or never,
 * and JKITE_ASSUME_YES or --yes answers yes in advance.
 */
class TestNetworkConsent extends AbstractScriptTest {

	/** A launcher with no jar next to it, pinned at an address nothing answers. */
	private Path launcherWithoutJar() throws IOException {
		Path dir = Files.createDirectories(tempDir.resolve("bin"));
		Path launcher = dir.resolve("jkite");
		Files.copy(BASH_SCRIPT, launcher, StandardCopyOption.REPLACE_EXISTING);
		Files.copy(BASH_SCRIPT.resolveSibling("jkite-bootstrap-jar"), dir.resolve("jkite-bootstrap-jar"),
				StandardCopyOption.REPLACE_EXISTING);
		Files.copy(BASH_SCRIPT.resolveSibling("jkite-bootstrap-jdk"), dir.resolve("jkite-bootstrap-jdk"),
				StandardCopyOption.REPLACE_EXISTING);
		Files.write(dir.resolve("jkite.properties"),
				("distributionVersion=9.9.9\n"
						+ "distributionUrl=https://127.0.0.1:1/nowhere/jkite.jar\n"
						+ "distributionSha256Sum=00\n"
						+ "bootstrapJdkVersion=99.0.0\n"
						+ "bootstrapJdkUrl." + indexPlatform() + "=https://127.0.0.1:1/nowhere/jdk.tar.gz\n"
						+ "bootstrapJdkSha256Sum." + indexPlatform() + "=00\n")
					.getBytes(StandardCharsets.UTF_8));
		return launcher;
	}

	private Path launcher;
	private Path script;

	@BeforeEach
	void setUp() throws Exception {
		requireBash();
		launcher = launcherWithoutJar();
		script = tempDir.resolve("Hello.java");
		Files.write(script, "class Hello {}".getBytes(StandardCharsets.UTF_8));
	}

	/** @param mode null to leave JKITE_CONFIRM_DOWNLOADS unset */
	private Map<String, String> env(String mode) {
		Map<String, String> env = baseBashEnv("consent");
		env.put("JAVA_HOME", System.getProperty("java.home"));
		env.remove("JKITE_ASSUME_YES");
		if (mode == null) {
			env.remove("JKITE_CONFIRM_DOWNLOADS");
		} else {
			env.put("JKITE_CONFIRM_DOWNLOADS", mode);
		}
		return env;
	}

	@Test
	void autoWithNobodyToAskSaysWhatItFetchesAndCarriesOn() throws Exception {
		RunResult result = runProcess(bashCmd(launcher, script.toString()), env(null));

		assertTrue(result.stderr.contains("jkite.jar 9.9.9"),
				"it should say what it is about to download: " + result.stderr);
		assertTrue(result.stderr.contains("Error downloading jkite"),
				"auto carries on when there is nobody to ask: " + result.stderr);
	}

	@Test
	void alwaysWithNobodyToAskFetchesNothing() throws Exception {
		RunResult result = runProcess(bashCmd(launcher, script.toString()), env("always"));

		assertNotEquals(0, result.exitCode);
		assertTrue(result.stderr.contains("jkite.jar 9.9.9"), result.stderr);
		assertTrue(result.stderr.contains("JKITE_CONFIRM_DOWNLOADS=always"),
				"it should name the setting that stopped it: " + result.stderr);
		assertTrue(!result.stderr.contains("Error downloading jkite"),
				"nothing should have been fetched: " + result.stderr);
	}

	@Test
	void neverGoesStraightToTheDownload() throws Exception {
		RunResult result = runProcess(bashCmd(launcher, script.toString()), env("never"));

		assertTrue(result.stderr.contains("Error downloading jkite"), result.stderr);
		assertTrue(!result.stderr.contains("jkite has to download"),
				"never says nothing: " + result.stderr);
	}

	@Test
	void assumeYesAnswersInAdvance() throws Exception {
		Map<String, String> env = env("always");
		env.put("JKITE_ASSUME_YES", "1");
		RunResult result = runProcess(bashCmd(launcher, script.toString()), env);

		assertTrue(result.stderr.contains("Error downloading jkite"),
				"always plus assume-yes downloads: " + result.stderr);
	}

	@Test
	void theYesOptionAnswersInAdvanceToo() throws Exception {
		RunResult result = runProcess(bashCmd(launcher, "--yes", script.toString()), env("always"));

		assertTrue(result.stderr.contains("Error downloading jkite"),
				"--yes reaches the launcher, not only the jar: " + result.stderr);
	}

	/**
	 * The launcher and the jar have to read this variable the same way, or the
	 * answer depends on which half of a run is asking. "Any value" would make
	 * JKITE_ASSUME_YES=0 mean yes, which is only noticed after a download.
	 */
	@Test
	void assumeYesIsNotJustAnyValue() throws Exception {
		Map<String, String> env = env("always");
		env.put("JKITE_ASSUME_YES", "0");
		RunResult result = runProcess(bashCmd(launcher, script.toString()), env);

		assertNotEquals(0, result.exitCode, result.stderr);
		assertTrue(result.stderr.contains("There is no terminal to ask on"),
				"JKITE_ASSUME_YES=0 answered yes: " + result.stderr);
	}

	@Test
	void anUnknownModeIsIgnoredWithAWarning() throws Exception {
		RunResult result = runProcess(bashCmd(launcher, script.toString()), env("maybe"));

		assertTrue(result.stderr.contains("Ignoring invalid JKITE_CONFIRM_DOWNLOADS"), result.stderr);
		assertTrue(result.stderr.contains("Error downloading jkite"),
				"an unknown mode falls back to auto: " + result.stderr);
	}

	@Test
	void aJarAndAJdkThatAreAlreadyHereAreNotAskedAbout() throws Exception {
		// the fake jar takes a command and an exit code, and exits with it
		RunResult result = runProcess(bashCmd(bashLauncherWithJar(), "exit", "0"), env(null));

		assertEquals(0, result.exitCode, result.stderr);
		assertTrue(!result.stderr.contains("has to download"),
				"nothing was missing, so nothing should have been said: " + result.stderr);
	}

	/**
	 * The Windows launcher has to gate the same downloads under the same
	 * contract; fixing only the bash one would let Windows through. It cannot be
	 * run here, so this reads it.
	 */
	/**
	 * --offline is documented as "never access the network", and the launcher
	 * fetches the jar and a JDK before the jar - which is where the option was
	 * read - ever runs. So the launcher has to honour it itself, or the promise
	 * is broken on exactly the run where it matters: the first one.
	 */
	@Test
	void offlineStopsTheLauncherBeforeItFetchesAnything() throws Exception {
		RunResult result = runProcess(bashCmd(launcher, "--offline", script.toString()), env(null));

		assertNotEquals(0, result.exitCode, "an offline run that needs a download must fail");
		assertTrue(result.stderr.contains("--offline was given"), result.stderr);
		assertTrue(!result.stderr.contains("Error downloading jkite"),
				"nothing may be fetched: " + result.stderr);
	}

	@Test
	void theShortSpellingOfOfflineWorksToo() throws Exception {
		RunResult result = runProcess(bashCmd(launcher, "-o", script.toString()), env(null));

		assertNotEquals(0, result.exitCode, result.stderr);
		assertTrue(result.stderr.contains("--offline was given"), result.stderr);
	}

	/**
	 * Only what comes before the script is jkite's. A tool with an "-o output"
	 * of its own is common enough that reading one as jkite's would put runs
	 * offline that never asked to be.
	 */
	@Test
	void anOptionAfterTheScriptBelongsToTheScript() throws Exception {
		RunResult result = runProcess(bashCmd(launcher, script.toString(), "-o", "out.txt"), env(null));

		assertTrue(!result.stderr.contains("--offline was given"),
				"the script's own -o was read as jkite's: " + result.stderr);
		assertTrue(result.stderr.contains("Error downloading jkite"),
				"the run should have gone on to fetch: " + result.stderr);
	}

	@Test
	void theWindowsLauncherUsesTheSameContract() throws Exception {
		String cmd = new String(Files.readAllBytes(CMD_SCRIPT), StandardCharsets.UTF_8);

		assertTrue(cmd.contains("JKITE_CONFIRM_DOWNLOADS"),
				"jkite.cmd does not look at JKITE_CONFIRM_DOWNLOADS");
		assertTrue(cmd.contains("\"%JKITE_ASSUME_YES%\"==\"1\""),
				"jkite.cmd reads JKITE_ASSUME_YES differently from the jar");
		assertTrue(cmd.contains("Continue? [Y/n]"), "jkite.cmd does not ask before downloading");
		assertTrue(cmd.contains("There is no terminal to ask on"),
				"jkite.cmd does not handle having nobody to ask");
		assertTrue(cmd.contains("--offline was given"), "jkite.cmd does not honour --offline");
		// the question belongs on the console, not in the tool's stdout
		assertTrue(cmd.contains(">CON echo jkite has to download:"),
				"jkite.cmd asks on stdout, which a pipe or a redirect would swallow");
	}
}
