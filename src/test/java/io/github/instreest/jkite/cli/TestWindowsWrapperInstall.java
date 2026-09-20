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
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.github.tomakehurst.wiremock.client.WireMock;

/**
 * The Windows half of {@link TestWrapperInstall}: install.cmd, the jar
 * bootstrap it installs, and the launcher's own --version and --update. These
 * are the paths that only cmd.exe can run, which is why this fork has a CI
 * workflow at all (see .github/workflows/ci.yml).
 * <p>
 * The scripts are served from disk as install.cmd receives them from
 * raw.githubusercontent.com, which hands out the bytes as git stores them. This
 * repository stores them with LF endings (.gitattributes), so these tests also
 * answer whether cmd.exe runs the .cmd scripts as a project actually gets them.
 */
@EnabledOnOs(OS.WINDOWS)
class TestWindowsWrapperInstall extends AbstractScriptTest {

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
		assertFalse(Files.exists(wrapper.resolve("jkite.jar")), "the jar must not be installed");
		assertEquals(FILES.size(), Files.list(wrapper).count(), "nothing but dist/ is installed");
	}

	@Test
	void theLauncherDownloadsTheJarAndRunsIt() throws Exception {
		assertEquals(0, install(project).exitCode);

		RunResult result = runLauncher("exit", "3");

		assertEquals(3, result.exitCode, result.stderr);
		assertTrue(result.stdout.contains("some output"), result.stdout);
		assertTrue(result.stderr.contains("some error output"), result.stderr);
		assertTrue(result.stderr.contains("Downloading jkite 9.9.9"), result.stderr);
		wm.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo(JAR_PATH)));
	}

	@Test
	void theJarIsCachedSoASecondRunDownloadsNothing() throws Exception {
		assertEquals(0, install(project).exitCode);

		assertEquals(0, runLauncher("exit", "0").exitCode);
		RunResult second = runLauncher("exit", "0");

		assertEquals(0, second.exitCode, second.stderr);
		assertFalse(second.stderr.contains("Downloading jkite"), second.stderr);
		wm.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo(JAR_PATH)));
	}

	@Test
	void aJarNextToTheLauncherIsUsedAndNothingIsDownloaded() throws Exception {
		assertEquals(0, install(project).exitCode);
		createFakeJar(project.resolve("jkite").resolve("jkite.jar"), "8.8.8");

		RunResult result = runLauncher("exit", "4");

		assertEquals(4, result.exitCode, result.stderr);
		assertFalse(result.stderr.contains("Downloading jkite"), result.stderr);
		wm.verify(0, WireMock.getRequestedFor(WireMock.urlEqualTo(JAR_PATH)));
	}

	@Test
	void aJarThatFailsItsChecksumIsRefused() throws Exception {
		wm.resetAll();
		serveDist(jar, sha256("something else".getBytes(StandardCharsets.UTF_8)), "9.9.9");
		assertEquals(0, install(project).exitCode);

		RunResult result = runLauncher("exit", "0");

		assertNotEquals(0, result.exitCode);
		assertTrue(result.stderr.contains("SHA-256 mismatch"), result.stderr);
		assertTrue(result.stderr.contains("distributionSha256Sum"), result.stderr);
	}

	@Test
	void theDistributionUrlCanBePointedAtAMirror() throws Exception {
		assertEquals(0, install(project).exitCode);
		wm.stubFor(WireMock.get(WireMock.urlEqualTo("/mirror/jkite.jar"))
			.willReturn(WireMock.aResponse().withStatus(200).withBody(jar)));
		Map<String, String> env = env();
		env.put("JKITE_DIST_URL", wm.baseUrl() + "/mirror/jkite.jar");

		RunResult result = runProcess(launcherCmd("exit", "0"), env);

		assertEquals(0, result.exitCode, result.stderr);
		wm.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/mirror/jkite.jar")));
		wm.verify(0, WireMock.getRequestedFor(WireMock.urlEqualTo(JAR_PATH)));
	}

	@Test
	void versionReportsThePinAndTheInstalledJarWithoutDownloading() throws Exception {
		assertEquals(0, install(project).exitCode);

		RunResult before = runLauncher("--version");
		assertEquals(0, before.exitCode, before.stderr);
		assertTrue(before.stdout.contains("jkite 9.9.9"), before.stdout);
		assertTrue(before.stdout.contains("pinned 9.9.9 by"), before.stdout);
		assertTrue(before.stdout.contains("jar not installed yet"), before.stdout);
		wm.verify(0, WireMock.getRequestedFor(WireMock.urlEqualTo(JAR_PATH)));

		assertEquals(0, runLauncher("exit", "0").exitCode);

		RunResult after = runLauncher("--version");
		assertEquals(0, after.exitCode, after.stderr);
		assertTrue(after.stdout.contains("jkite 9.9.9"), after.stdout);
		assertTrue(after.stdout.contains("pinned 9.9.9 by"), after.stdout);
		assertTrue(after.stdout.contains("jar 9.9.9 at"), after.stdout);
		wm.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo(JAR_PATH)));
	}

	@Test
	void versionSaysWhenAVendoredJarOverridesThePin() throws Exception {
		assertEquals(0, install(project).exitCode);
		createFakeJar(project.resolve("jkite").resolve("jkite.jar"), "8.8.8");

		RunResult result = runLauncher("--version");

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
		// a newer jkite was released ...
		wm.resetAll();
		serveDist(jar, sha256(jar), "9.9.10");

		// ... and --update brings this installation to it, without a JDK or a jar
		RunResult result = runLauncher("--update");

		assertEquals(0, result.exitCode, result.stderr);
		assertTrue(readProperties().contains("distributionVersion=9.9.10"), readProperties());
	}

	/**
	 * --update runs the installed install.cmd, and install.cmd is one of the
	 * files it installs, so it writes over the script cmd.exe is reading.
	 * cmd.exe keeps a byte offset into that file, so the failure only shows when
	 * the new release's installer differs in length from the installed one. The
	 * one served here is the real installer with a comment block inserted near
	 * the top, which shifts every later offset.
	 */
	@Test
	void updateSurvivesAnInstallerOfADifferentLength() throws Exception {
		assertEquals(0, install(project).exitCode);
		wm.resetAll();
		serveDist(jar, sha256(jar), "9.9.10");
		String installer = new String(Files.readAllBytes(DIST.resolve("install.cmd")), StandardCharsets.UTF_8);
		StringBuilder padding = new StringBuilder();
		for (int i = 0; i < 40; i++) {
			padding.append("rem a later release says more about itself than this one did\r\n");
		}
		int afterFirstLine = installer.indexOf('\n') + 1;
		byte[] longer = (installer.substring(0, afterFirstLine) + padding + installer.substring(afterFirstLine))
			.getBytes(StandardCharsets.UTF_8);
		wm.stubFor(WireMock.get(WireMock.urlEqualTo("/releases/latest/download/install.cmd"))
			.willReturn(WireMock.aResponse().withStatus(200).withBody(longer)));

		RunResult result = runLauncher("--update");

		assertEquals(0, result.exitCode, result.stderr);
		assertTrue(readProperties().contains("distributionVersion=9.9.10"),
				"the pin was not updated: " + result.stderr);
		assertArrayEquals(longer, Files.readAllBytes(project.resolve("jkite").resolve("install.cmd")),
				"the new installer was not the one left behind");
	}

	/**
	 * Windows has no execute bit, so git would record the shell scripts 644 and
	 * the jkite/ this produces would be unrunnable for everyone on macOS and
	 * Linux. The installer asks git to record the bit, which is the only place
	 * it can be set from here.
	 */
	@Test
	void theShellScriptsAreRecordedExecutableInGit() throws Exception {
		assertEquals(0, runProcess(Arrays.asList("git", "init"), env(), null, project).exitCode);

		assertEquals(0, install(project).exitCode);

		RunResult staged = runProcess(Arrays.asList("git", "ls-files", "-s"), env(), null, project);
		assertEquals(0, staged.exitCode, staged.stderr);
		for (String name : Arrays.asList("jkite/jkite", "jkite/jkite-bootstrap-jdk",
				"jkite/jkite-bootstrap-jar", "jkite/install.sh")) {
			assertTrue(Arrays.stream(staged.stdout.split("\\R"))
				.anyMatch(line -> line.endsWith("\t" + name) && line.startsWith("100755 ")),
					name + " is not recorded executable:\n" + staged.stdout);
		}
	}

	@Test
	void updateWarnsThatAVendoredJarStillWins() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path vendored = project.resolve("jkite").resolve("jkite.jar");
		createFakeJar(vendored, "8.8.8");

		RunResult result = runLauncher("--update");

		assertEquals(0, result.exitCode, result.stderr);
		assertTrue(result.stderr.contains("still runs"), result.stderr);
		assertTrue(Files.exists(vendored), "the vendored jar must not be deleted");
	}

	private String readProperties() throws Exception {
		return new String(Files.readAllBytes(project.resolve("jkite").resolve("jkite.properties")),
				StandardCharsets.UTF_8);
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

	private RunResult install(Path where) throws Exception {
		return runProcess(cmdCmd(DIST.resolve("install.cmd"), where.resolve("jkite").toString()), env());
	}

	private List<String> launcherCmd(String... args) {
		return cmdCmd(project.resolve("jkite").resolve("jkite.cmd"), args);
	}

	private RunResult runLauncher(String... args) throws Exception {
		return runProcess(launcherCmd(args), env());
	}

	private Map<String, String> env() {
		Map<String, String> env = baseBashEnv("wrapper-cmd");
		env.put("JKITE_DIST_BASEURL", wm.baseUrl() + "/releases/latest/download");
		env.put("JAVA_HOME", System.getProperty("java.home"));
		env.put("no_proxy", "localhost,127.0.0.1");
		env.put("NO_PROXY", "localhost,127.0.0.1");
		return env;
	}
}
