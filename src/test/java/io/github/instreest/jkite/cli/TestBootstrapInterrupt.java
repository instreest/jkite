package io.github.instreest.jkite.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.github.tomakehurst.wiremock.client.WireMock;

/**
 * What a Ctrl-C during a download leaves behind. The bootstrap scripts take a
 * directory lock that every jkite run on the machine waits on, so a lock left
 * by an interrupted run is not that run's problem: it is the next ten minutes
 * of every other project's runs, until somebody removes it by hand.
 *
 * The download these scripts ask the user to be patient about is exactly when
 * an interrupt is likely.
 */
@DisabledOnOs(OS.WINDOWS)
class TestBootstrapInterrupt extends AbstractScriptTest {

	private static final Path SCRIPTS = Paths.get("src/main/scripts").toAbsolutePath();
	private static final String VERSION = "9.9.9";

	private Path wrapper;
	private Path cacheDir;

	@BeforeEach
	void setUpInstallation() throws IOException {
		requireBash();
		wrapper = Files.createDirectories(tempSubDir("interrupt-wrapper"));
		for (String name : Arrays.asList("jkite", "jkite-bootstrap-jar", "jkite-bootstrap-jdk")) {
			Files.copy(SCRIPTS.resolve(name), wrapper.resolve(name));
		}
		Files.write(wrapper.resolve("jkite.properties"),
				("distributionVersion=" + VERSION + "\n"
						+ "distributionUrl=" + wm.baseUrl() + "/slow/jkite.jar\n"
						+ "distributionSha256Sum="
						+ "0000000000000000000000000000000000000000000000000000000000000000\n"
						+ "bootstrapJdkVersion=99.0.0\n"
						+ "bootstrapJdkUrl." + indexPlatform() + "=https://127.0.0.1:1/nowhere/jdk\n"
						+ "bootstrapJdkSha256Sum." + indexPlatform() + "=00\n")
					.getBytes(StandardCharsets.UTF_8));
		// a download that never finishes, which is what an interrupt interrupts
		wm.stubFor(WireMock.get(WireMock.urlEqualTo("/slow/jkite.jar"))
			.willReturn(WireMock.aResponse().withStatus(200).withFixedDelay(600_000).withBody("never")));
	}

	@Test
	void anInterruptedDownloadLeavesNoLockBehind() throws Exception {
		Map<String, String> env = baseBashEnv("interrupt");
		cacheDir = Paths.get(env.get("JKITE_CACHE_DIR"));
		Path lock = cacheDir.resolve("jkite").resolve(VERSION + ".lock");

		ProcessBuilder pb = new ProcessBuilder("bash", wrapper.resolve("jkite-bootstrap-jar").toString())
			.directory(wrapper.toFile())
			.redirectErrorStream(true);
		pb.environment().clear();
		pb.environment().putAll(env);
		Process process = pb.start();
		try {
			awaitLock(lock, process);

			// Ctrl-C reaches the whole foreground process group, so curl dies
			// as well as the shell. Signalling only the shell would leave it
			// waiting on a child that is still running, and the trap would not
			// be reached for as long as that took.
			process.descendants().forEach(ProcessHandle::destroy);
			process.destroy();
			assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the interrupted script never exited");
		} finally {
			process.descendants().forEach(ProcessHandle::destroyForcibly);
			process.destroyForcibly();
		}

		assertTrue(!Files.exists(lock),
				"the lock was left behind, so every later jkite run on this machine waits it out: " + lock);
		assertTrue(leftovers().isEmpty(), "a partial download was left in the cache: " + leftovers());
	}

	private void awaitLock(Path lock, Process process) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
		while (System.nanoTime() < deadline) {
			if (Files.isDirectory(lock)) {
				return;
			}
			if (!process.isAlive()) {
				fail("the script exited before it took the lock:\n" + output(process));
			}
			Thread.sleep(50);
		}
		fail("the script never took the lock at " + lock);
	}

	/** Anything this run wrote into the jar directory and did not take away. */
	private java.util.List<String> leftovers() throws IOException {
		Path jarDir = cacheDir.resolve("jkite").resolve(VERSION);
		if (!Files.isDirectory(jarDir)) {
			return java.util.Collections.emptyList();
		}
		try (Stream<Path> files = Files.list(jarDir)) {
			return files.map(p -> p.getFileName().toString()).sorted().collect(java.util.stream.Collectors.toList());
		}
	}

	private static String output(Process process) throws IOException {
		return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
	}
}
