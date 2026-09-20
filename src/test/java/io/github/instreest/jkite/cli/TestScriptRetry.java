package io.github.instreest.jkite.cli;

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
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

/**
 * Download retries in the bootstrap scripts, exercised on the jar download
 * because it is the one every platform makes. A WireMock server fails the
 * request a few times before serving the jar, and JKITE_DOWNLOAD_RETRY says
 * how many attempts the script may make.
 *
 * See https://github.com/jbangdev/jbang/issues/2459
 */
class TestScriptRetry extends AbstractScriptTest {

	private static final String JAR_PATH = "/releases/download/v9.9.9/jkite.jar";

	private Path wrapper;
	private byte[] jar;

	@BeforeEach
	void installTheScripts() throws Exception {
		requireBash();
		wrapper = Files.createDirectories(tempDir.resolve("jkite"));
		for (String name : Arrays.asList("jkite", "jkite-bootstrap-jar")) {
			Files.copy(BASH_SCRIPT.resolveSibling(name), wrapper.resolve(name));
		}
		Path fakeJar = tempDir.resolve("fake.jar");
		createFakeJar(fakeJar, "9.9.9");
		jar = Files.readAllBytes(fakeJar);
	}

	/** Fails {@code failCount} times with a 500, then serves the jar. */
	private void stubFlakyJar(int failCount) throws Exception {
		Files.write(wrapper.resolve("jkite.properties"),
				("distributionVersion=9.9.9\n"
						+ "distributionUrl=" + wm.baseUrl() + JAR_PATH + "\n"
						+ "distributionSha256Sum=" + sha256(jar) + "\n").getBytes(StandardCharsets.UTF_8));
		for (int i = 0; i < failCount; i++) {
			wm.stubFor(WireMock.get(WireMock.urlEqualTo(JAR_PATH))
				.inScenario("flaky")
				.whenScenarioStateIs(i == 0 ? Scenario.STARTED : "attempt-" + i)
				.willReturn(WireMock.aResponse().withStatus(500).withBody("Server Error"))
				.willSetStateTo("attempt-" + (i + 1)));
		}
		wm.stubFor(WireMock.get(WireMock.urlEqualTo(JAR_PATH))
			.inScenario("flaky")
			.whenScenarioStateIs(failCount == 0 ? Scenario.STARTED : "attempt-" + failCount)
			.willReturn(WireMock.aResponse().withStatus(200).withBody(jar)));
	}

	private static String sha256(byte[] bytes) throws Exception {
		StringBuilder hex = new StringBuilder();
		for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
			hex.append(String.format("%02x", b));
		}
		return hex.toString();
	}

	private Map<String, String> env(int retryCount) {
		Map<String, String> env = baseBashEnv("retry-" + retryCount);
		env.put("JKITE_DOWNLOAD_RETRY", String.valueOf(retryCount));
		env.put("JKITE_DOWNLOAD_RETRY_DELAY", "0");
		env.put("JAVA_HOME", System.getProperty("java.home"));
		env.put("no_proxy", "localhost,127.0.0.1");
		env.put("NO_PROXY", "localhost,127.0.0.1");
		return env;
	}

	@Test
	void theDownloadSucceedsAfterTransientFailures() throws Exception {
		stubFlakyJar(3);

		RunResult result = runProcess(bashCmd(wrapper.resolve("jkite"), "exit", "0"), env(5));

		assertEquals(0, result.exitCode, result.stderr);
		assertTrue(result.stderr.contains("Retry in"), result.stderr);
		assertTrue(result.stdout.contains("some output"), result.stdout);
		wm.verify(4, WireMock.getRequestedFor(WireMock.urlEqualTo(JAR_PATH)));
	}

	@Test
	void theDownloadFailsWhenTheRetriesAreExhausted() throws Exception {
		stubFlakyJar(10);

		RunResult result = runProcess(bashCmd(wrapper.resolve("jkite"), "exit", "0"), env(2));

		assertNotEquals(0, result.exitCode, "the script should have failed");
		assertTrue(result.stderr.contains("Download 2/3 failed"), result.stderr);
		assertTrue(result.stderr.contains("Error downloading jkite"), result.stderr);
		wm.verify(3, WireMock.getRequestedFor(WireMock.urlEqualTo(JAR_PATH)));
	}

	@Test
	void zeroRetriesMeansASingleAttempt() throws Exception {
		stubFlakyJar(1);

		RunResult result = runProcess(bashCmd(wrapper.resolve("jkite"), "exit", "0"), env(0));

		assertNotEquals(0, result.exitCode, "the script should have failed");
		assertFalse(result.stderr.contains("Retry in"), result.stderr);
		wm.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo(JAR_PATH)));
	}

	@Test
	void aPlaintextDownloadUrlIsRefused() throws Exception {
		Files.write(wrapper.resolve("jkite.properties"),
				("distributionVersion=9.9.9\n"
						+ "distributionUrl=http://example.invalid/jkite.jar\n"
						+ "distributionSha256Sum=" + sha256(jar) + "\n").getBytes(StandardCharsets.UTF_8));

		RunResult result = runProcess(bashCmd(wrapper.resolve("jkite"), "exit", "0"), env(0));

		assertNotEquals(0, result.exitCode, "the script should have failed");
		assertTrue(result.stderr.contains("anything but https"), result.stderr);
	}
}
