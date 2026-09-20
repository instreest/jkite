package io.github.instreest.jkite.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The Windows half of TestNetworkConsent. The two launchers publish one
 * contract - JKITE_CONFIRM_DOWNLOADS is auto, always or never, and
 * JKITE_ASSUME_YES or --yes answers yes in advance - and until now only the
 * POSIX half of it was tested. The contract is about not downloading things
 * behind somebody's back, so the half nobody checked is not a good half to
 * leave unchecked.
 *
 * Every test here runs with stdin a pipe, which is what a ProcessBuilder
 * gives. That is the "nobody to ask" case, and it is how jkite.cmd decides:
 * "timeout /t 0" fails when stdin is redirected. Note that this is not quite
 * what the POSIX launcher does, which opens the terminal device itself and so
 * still asks when only stdin was redirected - see
 * aRedirectedStdinIsTreatedAsHavingNoTerminal below.
 */
@EnabledOnOs(OS.WINDOWS)
class TestWindowsNetworkConsent extends AbstractScriptTest {

	private Path launcher;
	private Path script;

	/** A launcher with no jar next to it, pinned at an address nothing answers. */
	@BeforeEach
	void setUp() throws IOException {
		Path dir = Files.createDirectories(tempDir.resolve("bin"));
		launcher = dir.resolve("jkite.cmd");
		Files.copy(CMD_SCRIPT, launcher, StandardCopyOption.REPLACE_EXISTING);
		for (String name : new String[] { "jkite-bootstrap-jar.cmd", "jkite-bootstrap-jdk.cmd" }) {
			Files.copy(CMD_SCRIPT.resolveSibling(name), dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
		}
		Files.write(dir.resolve("jkite.properties"),
				("distributionVersion=9.9.9\r\n"
						+ "distributionUrl=https://127.0.0.1:1/nowhere/jkite.jar\r\n"
						+ "distributionSha256Sum=00\r\n"
						+ "bootstrapJdkVersion=99.0.0\r\n"
						+ "bootstrapJdkUrl." + indexPlatform() + "=https://127.0.0.1:1/nowhere/jdk.zip\r\n"
						+ "bootstrapJdkSha256Sum." + indexPlatform() + "=00\r\n")
					.getBytes(StandardCharsets.UTF_8));
		script = tempDir.resolve("Hello.java");
		Files.write(script, "class Hello {}".getBytes(StandardCharsets.UTF_8));
	}

	/** @param mode null to leave JKITE_CONFIRM_DOWNLOADS unset */
	private Map<String, String> env(String mode) {
		Map<String, String> env = new HashMap<>(System.getenv());
		env.put("JAVA_HOME", System.getProperty("java.home"));
		env.put("JKITE_DIR", tempDir.resolve("jkite-home").toString());
		env.put("JKITE_CACHE_DIR", tempDir.resolve("cache").toString());
		// no retries: the address is meant not to answer, and the first attempt
		// already knows that
		env.put("JKITE_DOWNLOAD_RETRY", "0");
		env.remove("JKITE_ASSUME_YES");
		if (mode == null) {
			env.remove("JKITE_CONFIRM_DOWNLOADS");
		} else {
			env.put("JKITE_CONFIRM_DOWNLOADS", mode);
		}
		return env;
	}

	private RunResult run(Map<String, String> env, String... args) throws Exception {
		return runProcess(cmdCmd(launcher, args), env);
	}

	@Test
	void autoWithNobodyToAskSaysWhatItFetchesAndCarriesOn() throws Exception {
		RunResult result = run(env(null), script.toString());

		assertTrue(result.stderr.contains("jkite.jar 9.9.9"),
				"it should say what it is about to download: " + result.stderr);
		assertTrue(result.stderr.contains("Error downloading jkite"),
				"auto carries on when there is nobody to ask: " + result.stderr);
	}

	@Test
	void alwaysWithNobodyToAskFetchesNothing() throws Exception {
		RunResult result = run(env("always"), script.toString());

		assertNotEquals(0, result.exitCode);
		assertTrue(result.stderr.contains("jkite.jar 9.9.9"), result.stderr);
		assertTrue(result.stderr.contains("JKITE_CONFIRM_DOWNLOADS=always"),
				"it should name the setting that stopped it: " + result.stderr);
		assertFalse(result.stderr.contains("Error downloading jkite"),
				"nothing should have been fetched: " + result.stderr);
	}

	@Test
	void neverGoesStraightToTheDownload() throws Exception {
		RunResult result = run(env("never"), script.toString());

		assertTrue(result.stderr.contains("Error downloading jkite"), result.stderr);
		assertFalse(result.stderr.contains("jkite has to download"),
				"never says nothing: " + result.stderr);
	}

	@Test
	void assumeYesAnswersInAdvance() throws Exception {
		Map<String, String> env = env("always");
		env.put("JKITE_ASSUME_YES", "1");

		RunResult result = run(env, script.toString());

		assertTrue(result.stderr.contains("Error downloading jkite"),
				"always plus assume-yes downloads: " + result.stderr);
	}

	@Test
	void theYesOptionAnswersInAdvanceToo() throws Exception {
		RunResult result = run(env("always"), "--yes", script.toString());

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

		RunResult result = run(env, script.toString());

		assertNotEquals(0, result.exitCode, result.stderr);
		assertTrue(result.stderr.contains("There is no terminal to ask on"),
				"JKITE_ASSUME_YES=0 answered yes: " + result.stderr);
	}

	@Test
	void anUnknownModeIsIgnoredWithAWarning() throws Exception {
		RunResult result = run(env("maybe"), script.toString());

		assertTrue(result.stderr.contains("Ignoring invalid JKITE_CONFIRM_DOWNLOADS"), result.stderr);
		assertTrue(result.stderr.contains("Error downloading jkite"),
				"an unknown mode falls back to auto: " + result.stderr);
	}

	/**
	 * Where the two launchers differ, recorded rather than asserted to be
	 * right.
	 *
	 * The POSIX launcher opens /dev/tty, so redirecting only stdin -
	 * "jkite Tool.java &lt; data.txt" at a real terminal - still gets a
	 * question. jkite.cmd decides from stdin, with "timeout /t 0", so the same
	 * invocation is treated as having no terminal: with always it refuses
	 * rather than asking, and with auto it goes ahead without asking. Both are
	 * safe, and neither is what README.md describes ("ask on a terminal").
	 *
	 * It is written down here rather than fixed because the fix cannot be
	 * judged from a Linux machine: asking on CON instead would hang forever on
	 * a runner that has a console with nobody behind it, which is worse than
	 * the wrong answer this gives, and cmd has no way to read a line with a
	 * deadline. Anyone changing it should make this test fail on purpose.
	 */
	@Test
	void aRedirectedStdinIsTreatedAsHavingNoTerminal() throws Exception {
		Path answers = tempDir.resolve("answers.txt");
		Files.write(answers, "y\r\n".getBytes(StandardCharsets.UTF_8));

		RunResult result = runProcess(cmdCmd(launcher, script.toString()), env("always"), answers);

		assertNotEquals(0, result.exitCode, result.stderr);
		assertTrue(result.stderr.contains("There is no terminal to ask on"),
				"a redirected stdin was taken for a terminal: " + result.stderr);
	}
}
