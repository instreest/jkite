package io.github.instreest.jkite.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.client.WireMock;

/**
 * Functional tests for the installer (dist/install.sh) and the jar bootstrap
 * it installs: a project gets the launcher scripts and jkite.properties,
 * and the jar is downloaded once per machine into the cache rather than
 * committed, unless the project vendors one next to the launcher.
 */
class TestWrapperInstall extends AbstractScriptTest {

	private static final Path DIST = Paths.get("dist").toAbsolutePath();
	private static final List<String> FILES = Arrays.asList("jkite", "jkite.cmd",
			"jkite-bootstrap-jdk", "jkite-bootstrap-jdk.cmd",
			"jkite-bootstrap-jar", "jkite-bootstrap-jar.cmd", "jkite.properties",
			"install.sh", "install.cmd", "README.md", "LICENSE");
	private static final String JAR_PATH = "/releases/download/v9.9.9/jkite.jar";

	private Path project;
	private byte[] jar;

	@BeforeEach
	void serveRepository() throws Exception {
		requireBash();
		project = Files.createDirectories(tempDir.resolve("project"));
		Path fakeJar = tempDir.resolve("fake.jar");
		createFakeJar(fakeJar, "9.9.9");
		jar = Files.readAllBytes(fakeJar);
		serveDist(jar, sha256(jar), "9.9.9");
	}

	@Test
	void installsTheScriptsAndThePropertiesButNoJar() throws Exception {
		RunResult result = install(project);
		assertEquals(0, result.exitCode, result.stderr);

		Path wrapper = project.resolve("jkite");
		for (String name : FILES) {
			assertTrue(Files.isRegularFile(wrapper.resolve(name)), name + " was not installed");
		}
		assertTrue(Files.isExecutable(wrapper.resolve("jkite")));
		assertTrue(Files.isExecutable(wrapper.resolve("jkite-bootstrap-jdk")));
		assertTrue(Files.isExecutable(wrapper.resolve("jkite-bootstrap-jar")));
		assertFalse(Files.exists(wrapper.resolve("jkite.jar")), "the jar must not be installed");
		assertEquals(FILES.size(), Files.list(wrapper).count(), "nothing but dist/ is installed");
	}

	@Test
	void theLauncherDownloadsTheJarAndRunsIt() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jkite");

		RunResult result = runLauncher(wrapper, "exit", "3");
		assertEquals(3, result.exitCode, result.stderr);
		assertTrue(result.stdout.contains("some output"), result.stdout);
		assertTrue(result.stderr.contains("some error output"), result.stderr);
		assertTrue(result.stderr.contains("Downloading jkite 9.9.9"), result.stderr);
		wm.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo(JAR_PATH)));
	}

	@Test
	void theJarIsCachedSoASecondRunDownloadsNothing() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jkite");
		Map<String, String> env = env();

		assertEquals(0, runProcess(bashCmd(wrapper.resolve("jkite"), "exit", "0"), env).exitCode);
		RunResult second = runProcess(bashCmd(wrapper.resolve("jkite"), "exit", "0"), env);

		assertEquals(0, second.exitCode, second.stderr);
		assertFalse(second.stderr.contains("Downloading jkite"), second.stderr);
		wm.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo(JAR_PATH)));
	}

	@Test
	void aJarNextToTheLauncherIsUsedAndNothingIsDownloaded() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jkite");
		createFakeJar(wrapper.resolve("jkite.jar"));

		RunResult result = runLauncher(wrapper, "exit", "4");

		assertEquals(4, result.exitCode, result.stderr);
		assertFalse(result.stderr.contains("Downloading jkite"), result.stderr);
		wm.verify(0, WireMock.getRequestedFor(WireMock.urlEqualTo(JAR_PATH)));
	}

	@Test
	void aJarThatFailsItsChecksumIsRefused() throws Exception {
		wm.resetAll();
		serveDist(jar, sha256("something else".getBytes(StandardCharsets.UTF_8)), "9.9.9");
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jkite");

		RunResult result = runLauncher(wrapper, "exit", "0");

		assertNotEquals(0, result.exitCode);
		assertTrue(result.stderr.contains("SHA-256 mismatch"), result.stderr);
		assertTrue(result.stderr.contains("distributionSha256Sum"), result.stderr);
	}

	@Test
	void theDistributionUrlCanBePointedAtAMirror() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jkite");
		wm.stubFor(WireMock.get(WireMock.urlEqualTo("/mirror/jkite.jar"))
			.willReturn(WireMock.aResponse().withStatus(200).withBody(jar)));
		Map<String, String> env = env();
		env.put("JKITE_DIST_URL", wm.baseUrl() + "/mirror/jkite.jar");

		RunResult result = runProcess(bashCmd(wrapper.resolve("jkite"), "exit", "0"), env);

		assertEquals(0, result.exitCode, result.stderr);
		wm.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/mirror/jkite.jar")));
		wm.verify(0, WireMock.getRequestedFor(WireMock.urlEqualTo(JAR_PATH)));
	}

	@Test
	void rerunningTheInstallerUpdatesInPlace() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jkite");

		// a newer revision was published ...
		wm.resetAll();
		serveDist(jar, "0000000000000000000000000000000000000000000000000000000000000000", "9.9.9");

		// ... and running the installed copy updates the directory it lives in
		RunResult result = runProcess(Arrays.asList("bash", wrapper.resolve("install.sh").toString()), env());
		assertEquals(0, result.exitCode, result.stderr);
		assertTrue(result.stderr.contains(wrapper.toString()), result.stderr);
		assertTrue(new String(Files.readAllBytes(wrapper.resolve("jkite.properties")), StandardCharsets.UTF_8)
			.contains("0000000000000000000000000000000000000000000000000000000000000000"));
	}

	@Test
	void aFailedDownloadLeavesAnInstallationAlone() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jkite");
		byte[] before = Files.readAllBytes(wrapper.resolve("jkite.properties"));
		wm.resetAll();
		wm.stubFor(WireMock.get(WireMock.anyUrl()).willReturn(WireMock.aResponse().withStatus(404)));

		RunResult result = runProcess(Arrays.asList("bash", wrapper.resolve("install.sh").toString()), env());
		assertTrue(result.exitCode != 0, result.stderr);
		assertArrayEquals(before, Files.readAllBytes(wrapper.resolve("jkite.properties")));
	}

	@Test
	void versionReportsThePinAndTheInstalledJarWithoutDownloading() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jkite");

		RunResult before = runProcess(bashCmd(wrapper.resolve("jkite"), "--version"), env());
		assertEquals(0, before.exitCode, before.stderr);
		assertTrue(before.stdout.contains("jkite 9.9.9"), before.stdout);
		assertTrue(before.stdout.contains("pinned 9.9.9 by"), before.stdout);
		assertTrue(before.stdout.contains("jar not installed yet"), before.stdout);
		wm.verify(0, WireMock.getRequestedFor(WireMock.urlEqualTo(JAR_PATH)));

		assertEquals(0, runProcess(bashCmd(wrapper.resolve("jkite"), "exit", "0"), env()).exitCode);

		RunResult after = runProcess(bashCmd(wrapper.resolve("jkite"), "--version"), env());
		assertEquals(0, after.exitCode, after.stderr);
		assertTrue(after.stdout.contains("jkite 9.9.9"), after.stdout);
		assertTrue(after.stdout.contains("pinned 9.9.9 by"), after.stdout);
		assertTrue(after.stdout.contains("jar 9.9.9 at"), after.stdout);
		wm.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo(JAR_PATH)));
	}

	@Test
	void versionSaysWhenAVendoredJarOverridesThePin() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jkite");
		createFakeJar(wrapper.resolve("jkite.jar"), "8.8.8");

		RunResult result = runProcess(bashCmd(wrapper.resolve("jkite"), "--version"), env());

		assertEquals(0, result.exitCode, result.stderr);
		// the vendored jar is the one that runs, so it names the version. When it
		// could not be asked, the launcher says why on stderr, so show both.
		String said = result.stdout + result.stderr;
		assertTrue(result.stdout.contains("jkite 8.8.8"), said);
		assertTrue(result.stdout.contains("pinned 9.9.9 by"), said);
		assertTrue(result.stdout.contains("jar 8.8.8 at"), said);
		assertTrue(result.stdout.contains("vendored, so this jar runs and not the pinned version"), result.stdout);
		wm.verify(0, WireMock.getRequestedFor(WireMock.urlEqualTo(JAR_PATH)));
	}

	@Test
	void updateReinstallsTheDirectoryInPlace() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jkite");
		// a newer jkite was released ...
		wm.resetAll();
		serveDist(jar, sha256(jar), "9.9.10");

		// ... and --update brings this installation to it, without a JDK or a jar
		RunResult result = runProcess(bashCmd(wrapper.resolve("jkite"), "--update"), env());

		assertEquals(0, result.exitCode, result.stderr);
		assertTrue(new String(Files.readAllBytes(wrapper.resolve("jkite.properties")), StandardCharsets.UTF_8)
			.contains("distributionVersion=9.9.10"), "the pin was not updated");
	}

	/**
	 * --update runs the installed install.sh, and install.sh is one of the files
	 * it installs, so it writes over the script bash is reading. That only shows
	 * when the new release's installer differs in length from the installed one:
	 * bash resumes at the byte offset it had saved, which now falls in the
	 * middle of something else. The installer served here is the real one with a
	 * comment block inserted near the top, so every later offset is shifted.
	 */
	@Test
	void updateSurvivesAnInstallerOfADifferentLength() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jkite");
		wm.resetAll();
		serveDist(jar, sha256(jar), "9.9.10");
		String installer = new String(Files.readAllBytes(DIST.resolve("install.sh")), StandardCharsets.UTF_8);
		StringBuilder padding = new StringBuilder("\n");
		for (int i = 0; i < 40; i++) {
			padding.append("# a later release says more about itself than this one did\n");
		}
		int afterShebang = installer.indexOf('\n') + 1;
		byte[] longer = (installer.substring(0, afterShebang) + padding + installer.substring(afterShebang))
			.getBytes(StandardCharsets.UTF_8);
		wm.stubFor(WireMock.get(WireMock.urlEqualTo("/releases/latest/download/install.sh"))
			.willReturn(WireMock.aResponse().withStatus(200).withBody(longer)));

		RunResult result = runProcess(bashCmd(wrapper.resolve("jkite"), "--update"), env());

		assertEquals(0, result.exitCode, result.stderr);
		assertTrue(new String(Files.readAllBytes(wrapper.resolve("jkite.properties")), StandardCharsets.UTF_8)
			.contains("distributionVersion=9.9.10"), "the pin was not updated: " + result.stderr);
		assertArrayEquals(longer, Files.readAllBytes(wrapper.resolve("install.sh")),
				"the new installer was not the one left behind");
		assertTrue(Files.isExecutable(wrapper.resolve("install.sh")), "the installed script lost its execute bit");
	}

	@Test
	void updateWarnsThatAVendoredJarStillWins() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jkite");
		createFakeJar(wrapper.resolve("jkite.jar"));

		RunResult result = runProcess(bashCmd(wrapper.resolve("jkite"), "--update"), env());

		assertEquals(0, result.exitCode, result.stderr);
		assertTrue(result.stderr.contains("still runs"), result.stderr);
		assertTrue(Files.exists(wrapper.resolve("jkite.jar")), "the vendored jar must not be deleted");
	}

	/**
	 * dist/ is what a project installs, so the scripts copied there must be the
	 * ones in src/main/scripts (misc/update-dist.sh refreshes them). The jar is
	 * a release asset and is deliberately absent.
	 */
	@Test
	void distHoldsTheCurrentScriptsAndNoJar() throws Exception {
		for (String name : Arrays.asList("jkite", "jkite.cmd", "jkite-bootstrap-jdk",
				"jkite-bootstrap-jdk.cmd", "jkite-bootstrap-jar", "jkite-bootstrap-jar.cmd")) {
			assertArrayEquals(Files.readAllBytes(BASH_SCRIPT.resolveSibling(name)),
					Files.readAllBytes(DIST.resolve(name)),
					"dist/" + name + " is out of date, run misc/update-dist.sh");
		}
		assertArrayEquals(Files.readAllBytes(Paths.get("LICENSE")), Files.readAllBytes(DIST.resolve("LICENSE")),
				"dist/LICENSE is out of date, run misc/update-dist.sh");
		assertTrue(Files.isRegularFile(DIST.resolve("jkite.properties")), "dist/jkite.properties is missing");
		assertFalse(Files.exists(DIST.resolve("jkite.jar")),
				"dist/jkite.jar must not be committed, it is a release asset");
	}

	/**
	 * Serves dist/ as the repository would, with a jkite.properties that
	 * points the bootstrap script at this server instead of at GitHub.
	 */
	private void serveDist(byte[] jar, String sha256, String version) throws Exception {
		for (String name : FILES) {
			byte[] body = name.equals("jkite.properties")
					? ("distributionVersion=" + version + "\n"
							+ "distributionUrl=" + wm.baseUrl() + JAR_PATH + "\n"
							+ "distributionSha256Sum=" + sha256 + "\n"
							+ "bootstrapJdkVersion=99.0.0\n"
							+ "bootstrapJdkUrl." + indexPlatform() + "=https://127.0.0.1:1/nowhere/jdk\n"
							+ "bootstrapJdkSha256Sum." + indexPlatform() + "=00\n").getBytes(StandardCharsets.UTF_8)
					: Files.readAllBytes(DIST.resolve(name));
			wm.stubFor(WireMock.get(WireMock.urlEqualTo("/releases/latest/download/" + name))
				.willReturn(WireMock.aResponse().withStatus(200).withBody(body)));
		}
		wm.stubFor(WireMock.get(WireMock.urlEqualTo(JAR_PATH))
			.willReturn(WireMock.aResponse().withStatus(200).withBody(jar)));
	}

	private static String sha256(byte[] bytes) throws Exception {
		StringBuilder hex = new StringBuilder();
		for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
			hex.append(String.format("%02x", b));
		}
		return hex.toString();
	}

	/**
	 * A download that fails says so in a sentence, and exits 1.
	 *
	 * "set -e" ends the run on curl's own exit code - 22 for an HTTP error -
	 * and prints only "curl: (22) The requested URL returned error: 404",
	 * which names neither the file nor the release it was not in, and hands
	 * back a status that is not one of jkite's. install.cmd already got this
	 * right; install.sh did not.
	 */
	@Test
	void aFailedDownloadIsExplainedRatherThanLeftToCurl() throws Exception {
		wm.resetAll();
		wm.stubFor(WireMock.get(WireMock.urlMatching("/releases/latest/download/.*"))
			.willReturn(WireMock.aResponse().withStatus(404)));

		RunResult result = install(project);

		assertEquals(1, result.exitCode, result.stderr);
		assertTrue(result.stderr.contains("Could not download"), result.stderr);
		assertFalse(Files.exists(project.resolve("jkite")) && Files.list(project.resolve("jkite")).findAny().isPresent(),
				"a failed install left files behind: " + project.resolve("jkite"));
	}

	private RunResult install(Path where) throws Exception {
		return runProcess(Arrays.asList("bash", DIST.resolve("install.sh").toString(),
				where.resolve("jkite").toString()), env());
	}

	private RunResult runLauncher(Path wrapper, String... args) throws Exception {
		return runProcess(bashCmd(wrapper.resolve("jkite"), args), env());
	}

	private Map<String, String> env() {
		Map<String, String> env = baseBashEnv("wrapper");
		env.put("JKITE_DIST_BASEURL", wm.baseUrl() + "/releases/latest/download");
		env.put("JAVA_HOME", System.getProperty("java.home"));
		env.put("no_proxy", "localhost,127.0.0.1");
		env.put("NO_PROXY", "localhost,127.0.0.1");
		return env;
	}
}
