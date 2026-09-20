package io.github.instreest.jkite.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * Shared infrastructure for functional tests of jkite's launcher scripts. Provides
 * WireMock lifecycle, process execution helpers, archive creation utilities,
 * and a base environment map for the tests.
 */
abstract class AbstractScriptTest {

	protected static final Path BASH_SCRIPT = Paths.get("src/main/scripts/jkite").toAbsolutePath();
	protected static final Path CMD_SCRIPT = Paths.get("src/main/scripts/jkite.cmd").toAbsolutePath();

	protected WireMockServer wm;

	@TempDir
	protected Path tempDir;

	@BeforeEach
	void startWireMock() {
		wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		wm.start();
	}

	@AfterEach
	void stopWireMock() {
		if (wm != null) {
			wm.stop();
		}
	}

	/**
	 * Creates a unique subdirectory path under {@link #tempDir}. The directory is
	 * not created on disk — startup scripts will create it as needed.
	 */
	protected Path tempSubDir(String name) {
		return tempDir.resolve(name);
	}

	// -------------------------------------------------------------------------
	// Command availability checks
	// -------------------------------------------------------------------------

	protected static boolean isCommandAvailable(String command) {
		try {
			Process p = new ProcessBuilder(command, "--version")
				.redirectErrorStream(true)
				.start();
			p.getInputStream().transferTo(new ByteArrayOutputStream());
			return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
		} catch (Exception e) {
			return false;
		}
	}

	protected void requireBash() {
		assumeTrue(isCommandAvailable("bash"), "bash is not available");
	}

	// -------------------------------------------------------------------------
	// Process execution
	// -------------------------------------------------------------------------

	protected static RunResult runProcess(List<String> cmd, Map<String, String> env) throws Exception {
		return runProcess(cmd, env, null);
	}

	/** Runs the command with the given file as its stdin (none when null). */
	protected static RunResult runProcess(List<String> cmd, Map<String, String> env, Path stdin) throws Exception {
		return runProcess(cmd, env, stdin, null);
	}

	/**
	 * Runs the command with the given file as its stdin (none when null) in the
	 * given directory (this process's own when null). The directory matters
	 * where the program looks at where it was started from, as javac does when
	 * it is left to find sources by itself.
	 */
	protected static RunResult runProcess(List<String> cmd, Map<String, String> env, Path stdin, Path directory)
			throws Exception {
		ProcessBuilder pb = new ProcessBuilder(cmd);
		if (stdin != null) {
			pb.redirectInput(stdin.toFile());
		}
		if (directory != null) {
			pb.directory(directory.toFile());
		}
		// the map is the whole environment: what the caller removed stays removed
		pb.environment().clear();
		pb.environment().putAll(env);
		pb.redirectErrorStream(false);
		Process process = pb.start();

		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		Thread t1 = new Thread(() -> {
			try {
				process.getInputStream().transferTo(stdout);
			} catch (Exception e) {
				/* ignore */ }
		});
		Thread t2 = new Thread(() -> {
			try {
				process.getErrorStream().transferTo(stderr);
			} catch (Exception e) {
				/* ignore */ }
		});
		t1.start();
		t2.start();

		boolean finished = process.waitFor(120, TimeUnit.SECONDS);
		if (!finished) {
			process.destroyForcibly();
		}
		t1.join(5000);
		t2.join(5000);
		assertTrue(finished, "script timed out");
		return new RunResult(process.exitValue(),
				stdout.toString(StandardCharsets.UTF_8),
				stderr.toString(StandardCharsets.UTF_8));
	}

	static class RunResult {
		final int exitCode;
		final String stdout;
		final String stderr;

		RunResult(int exitCode, String stdout, String stderr) {
			this.exitCode = exitCode;
			this.stdout = stdout;
			this.stderr = stderr;
		}
	}

	// -------------------------------------------------------------------------
	// A launcher with a stand-in jar next to it
	// -------------------------------------------------------------------------

	/**
	 * Copies the bash launcher and its bootstrap script into a directory of
	 * their own with a fake jkite.jar next to them, as installed into a
	 * project, and returns the launcher.
	 */
	protected Path bashLauncherWithJar() throws IOException {
		Path dir = Files.createDirectories(tempDir.resolve("bin"));
		Path launcher = dir.resolve("jkite");
		Files.copy(BASH_SCRIPT, launcher, StandardCopyOption.REPLACE_EXISTING);
		Files.copy(BASH_SCRIPT.resolveSibling("jkite-bootstrap-jdk"), dir.resolve("jkite-bootstrap-jdk"),
				StandardCopyOption.REPLACE_EXISTING);
		// the JDK the bootstrap script would install, pinned at an address that
		// nothing answers, so a test that reaches it fails fast instead of
		// fetching 200 MB
		Files.write(dir.resolve("jkite.properties"),
				("bootstrapJdkVersion=99.0.0\n"
						+ "bootstrapJdkUrl." + indexPlatform() + "=https://127.0.0.1:1/nowhere/jdk.tar.gz\n"
						+ "bootstrapJdkSha256Sum." + indexPlatform() + "=0000000000000000000000000000000000000000000000000000000000000000\n")
					.getBytes(StandardCharsets.UTF_8));
		createFakeJar(dir.resolve("jkite.jar"));
		return launcher;
	}

	/** The name this platform has in jkite.properties, e.g. linux-amd64. */
	protected static String indexPlatform() {
		String os = System.getProperty("os.name").toLowerCase();
		String name = os.contains("mac") ? "darwin" : os.contains("win") ? "windows" : "linux";
		String arch = Arrays.asList("aarch64", "arm64").contains(System.getProperty("os.arch")) ? "arm64" : "amd64";
		return name + "-" + arch;
	}

	/** Writes a jar whose main class is {@link FakeJBang}. */
	protected static void createFakeJar(Path jar) throws IOException {
		createFakeJar(jar, null);
	}

	/**
	 * Writes a jar whose main class is {@link FakeJBang}, stamped with the given
	 * Jkite-Version when one is given, as a real jkite.jar is: the launchers
	 * ask the jar for its version by running it, and this is what it answers
	 * from.
	 */
	protected static void createFakeJar(Path jar, String jkiteVersion) throws IOException {
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FakeJBang.class.getName());
		if (jkiteVersion != null) {
			manifest.getMainAttributes().putValue("Jkite-Version", jkiteVersion);
		}
		String classResource = FakeJBang.class.getName().replace('.', '/') + ".class";
		try (InputStream input = FakeJBang.class.getClassLoader().getResourceAsStream(classResource);
				JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
			if (input == null) {
				throw new IOException("Could not find test class: " + classResource);
			}
			output.putNextEntry(new JarEntry(classResource));
			input.transferTo(output);
			output.closeEntry();
		}
	}

	/**
	 * Stand-in for jkite.jar: writes to stdout and stderr and exits with the
	 * given code ("exit N"), and answers --version with the Jkite-Version in its
	 * own manifest, as the real jar does. A launcher has nothing else to do with
	 * the jar than to run it, so this is all a launcher test needs.
	 */
	public static class FakeJBang {
		public static void main(String[] args) throws IOException {
			if (args.length > 0 && args[0].equals("--version")) {
				try (InputStream in = FakeJBang.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
					System.out.println(new Manifest(in).getMainAttributes().getValue("Jkite-Version"));
				}
				return;
			}
			System.out.println("some output");
			System.err.println("some error output");
			System.exit(Integer.parseInt(args[1]));
		}
	}

	// -------------------------------------------------------------------------
	// Base environment maps
	// -------------------------------------------------------------------------

	/**
	 * Returns a base environment map for the tests with JKITE_DIR,
	 * JKITE_CACHE_DIR set. JAVA_HOME is removed.
	 * Subclasses should add their specific env vars on top.
	 */
	protected Map<String, String> baseBashEnv(String suffix) {
		Path jbdir = tempSubDir("jbdir-" + suffix);
		Path tdir = tempSubDir("cache-" + suffix);
		Map<String, String> env = new HashMap<>(System.getenv());
		env.put("JKITE_DIR", jbdir.toString());
		env.put("JKITE_CACHE_DIR", tdir.toString());
		// No retries by default. A launcher test that reaches a download reaches
		// one that is meant to fail, and the retries then cost 1+2+4+8+16
		// seconds of sleeping for a result the first attempt already had. Five
		// tests in TestNetworkConsent alone were spending 31 seconds each that
		// way - 85% of the whole suite's time. TestScriptRetry, which is about
		// retrying, sets its own count over this one.
		env.put("JKITE_DOWNLOAD_RETRY", "0");
		env.remove("JAVA_HOME");
		return env;
	}


	/**
	 * A PATH with everything the launcher needs (coreutils, curl) but no java:
	 * one directory of links to the tools on the real PATH, java left out.
	 */
	protected String pathWithoutJava() {
		try {
			Path bin = Files.createDirectories(tempDir.resolve("path-without-java"));
			for (String dir : System.getenv("PATH").split(File.pathSeparator)) {
				Path d = Paths.get(dir);
				if (!Files.isDirectory(d)) {
					continue;
				}
				try (Stream<Path> tools = Files.list(d)) {
					for (Path tool : (Iterable<Path>) tools::iterator) {
						String name = tool.getFileName().toString();
						if (name.startsWith("java") || Files.exists(bin.resolve(name))) {
							continue;
						}
						Files.createSymbolicLink(bin.resolve(name), tool.toAbsolutePath());
					}
				}
			}
			return bin.toString();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// -------------------------------------------------------------------------
	// Command builders
	// -------------------------------------------------------------------------

	/**
	 * Builds a command list for running a bash launcher.
	 */
	protected List<String> bashCmd(Path launcher, String... args) {
		List<String> cmd = new ArrayList<>();
		cmd.add("bash");
		cmd.add(launcher.toString());
		for (String arg : args) {
			cmd.add(arg);
		}
		return cmd;
	}

	/**
	 * Builds a command list for running a .cmd script, the way a user would from
	 * a command prompt.
	 */
	protected List<String> cmdCmd(Path script, String... args) {
		List<String> cmd = new ArrayList<>();
		cmd.add("cmd.exe");
		cmd.add("/c");
		cmd.add(script.toString());
		for (String arg : args) {
			cmd.add(arg);
		}
		return cmd;
	}

}
