package io.github.instreest.jkite.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Which Java the bash launcher runs jkite.jar with.
 *
 * It looks in three places, in this order: the JDK a previous run downloaded,
 * JAVA_HOME, and the javac on the PATH. Each is there for a case that happens -
 * a second run, a developer's machine, a machine where the JDK is managed by
 * jenv or SDKMAN - and picking the wrong one costs either a wrong Java or a
 * 190 MB download that was not needed.
 *
 * {@link TestWindowsLaunchers} is the same search in cmd, and the two have to
 * agree.
 */
@DisabledOnOs(OS.WINDOWS)
class TestLaunchers extends AbstractScriptTest {

	private Path launcher;

	@BeforeEach
	void setUpLauncher() throws IOException {
		launcher = bashLauncherWithJar();
	}

	/** The environment a run starts from: our own cache, and a real JAVA_HOME. */
	private Map<String, String> env() {
		Map<String, String> env = baseBashEnv("launchers");
		env.put("JAVA_HOME", System.getProperty("java.home"));
		return env;
	}

	private RunResult run(Map<String, String> env, String... args) throws Exception {
		return runProcess(bashCmd(launcher, args), env);
	}

	/**
	 * A directory that looks like a JDK of the given version to the launcher: a
	 * java and a javac it can execute, and the 'release' file the version is
	 * read from. Nothing in it runs - it is only ever inspected.
	 *
	 * @param version null to leave out the release file, as a JDK whose version
	 *                cannot be read
	 */
	private String fakeJdk(String name, String version) throws IOException {
		Path jdk = Files.createDirectories(tempDir.resolve(name));
		Path bin = Files.createDirectories(jdk.resolve("bin"));
		for (String tool : Arrays.asList("java", "javac")) {
			Path f = bin.resolve(tool);
			Files.write(f, "#!/bin/sh\nexit 1\n".getBytes(StandardCharsets.UTF_8));
			executable(f);
		}
		if (version != null) {
			Files.write(jdk.resolve("release"), Arrays.asList("JAVA_VERSION=\"" + version + "\""),
					StandardCharsets.UTF_8);
		}
		return jdk.toString();
	}

	private static void executable(Path f) throws IOException {
		Set<PosixFilePermission> perms = EnumSet.copyOf(Files.getPosixFilePermissions(f));
		perms.add(PosixFilePermission.OWNER_EXECUTE);
		Files.setPosixFilePermissions(f, perms);
	}

	@Test
	void theExitCodeAndTheOutputOfTheJarPassStraightThrough() throws Exception {
		RunResult result = run(env(), "exit", "3");

		assertEquals(3, result.exitCode, result.stderr);
		assertTrue(result.stdout.contains("some output"), result.stdout);
		assertTrue(result.stderr.contains("some error output"), result.stderr);
	}

	@Test
	void aJavaHomeTooOldToRunTheJarIsIgnored() throws Exception {
		Map<String, String> env = env();
		env.put("JAVA_HOME", fakeJdk("java8", "1.8.0_292"));

		// the javac on the PATH takes over, so the jar still runs
		RunResult result = run(env, "exit", "3");

		assertEquals(3, result.exitCode, result.stderr);
		assertTrue(result.stderr.contains("JAVA_HOME is set but does not point to"), result.stderr);
	}

	@Test
	void aJavaHomeWhoseVersionCannotBeReadIsIgnored() throws Exception {
		Map<String, String> env = env();
		env.put("JAVA_HOME", fakeJdk("norelease", null));

		RunResult result = run(env, "exit", "3");

		assertEquals(3, result.exitCode, result.stderr);
		assertTrue(result.stderr.contains("JAVA_HOME is set but does not point to"), result.stderr);
	}

	/** Scripts have to be compiled, so a runtime without javac is no use. */
	@Test
	void aJavaHomeWithNoCompilerIsIgnored() throws Exception {
		String jre = fakeJdk("jre", "21.0.1");
		Files.delete(Paths.get(jre).resolve("bin/javac"));
		Map<String, String> env = env();
		env.put("JAVA_HOME", jre);

		RunResult result = run(env, "exit", "3");

		assertEquals(3, result.exitCode, result.stderr);
		assertTrue(result.stderr.contains("JAVA_HOME is set but does not point to"), result.stderr);
	}

	/**
	 * The JDK a previous run downloaded is asked for first, so a machine whose
	 * JAVA_HOME jkite cannot use settles down after one download instead of
	 * complaining on every run.
	 */
	@Test
	void theJdkFromAnEarlierRunComesBeforeJavaHome() throws Exception {
		Map<String, String> env = env();
		Path jdks = Files.createDirectories(tempDir.resolve("cache-launchers/jdks"));
		Files.createSymbolicLink(jdks.resolve("bootstrap"), Paths.get(System.getProperty("java.home")));
		env.put("JAVA_HOME", fakeJdk("java8", "1.8.0_292"));

		RunResult result = run(env, "exit", "3");

		assertEquals(3, result.exitCode, result.stderr);
		assertFalse(result.stderr.contains("JAVA_HOME"), "JAVA_HOME was looked at: " + result.stderr);
	}

	/**
	 * What is on the PATH as javac is often not the JDK's own: jenv, SDKMAN and
	 * the Windows javapath stub all put a shim there, and its directory is not a
	 * JDK. So javac is asked where its home is rather than having it read off
	 * its own path - this is the case that answer exists for.
	 */
	@Test
	void theJavacOnThePathIsAskedWhereItsHomeIs() throws Exception {
		Path shimDir = Files.createDirectories(tempDir.resolve("shims"));
		Path javac = shimDir.resolve("javac");
		Files.write(javac,
				("#!/bin/sh\nexec \"" + System.getProperty("java.home") + "/bin/javac\" \"$@\"\n")
					.getBytes(StandardCharsets.UTF_8));
		executable(javac);

		Map<String, String> env = env();
		env.remove("JAVA_HOME");
		env.put("PATH", shimDir + java.io.File.pathSeparator + pathWithoutJava());

		RunResult result = run(env, "exit", "3");

		assertEquals(3, result.exitCode, result.stderr);
	}

	/**
	 * Nothing usable anywhere, so the JDK jkite.properties pins is installed.
	 * It is pinned at an address nothing answers, so this fails instead of
	 * fetching 190 MB.
	 */
	@Test
	void aJdkIsInstalledWhenNothingOnTheMachineWillDo() throws Exception {
		Map<String, String> env = env();
		env.put("JAVA_HOME", fakeJdk("java8", "1.8.0_292"));
		env.put("PATH", pathWithoutJava());

		RunResult result = run(env, "exit", "3");

		assertTrue(result.exitCode != 0, result.stderr);
		assertTrue(result.stderr.contains("JAVA_HOME is set but does not point to"), result.stderr);
		assertTrue(result.stderr.contains("Error downloading the JDK"), result.stderr);
	}
}
