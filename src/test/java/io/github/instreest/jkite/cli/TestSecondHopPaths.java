package io.github.instreest.jkite.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The second hop, which the launcher tests never cross.
 *
 * A run is two process starts, not one. The launcher starts jkite.jar, and
 * jkite.jar starts a JVM for the script - a different command line, built by
 * CmdGenerator, carrying the built jar and every dependency jar on
 * -classpath. jkite.cmd was taught to hand java an 8.3 path because java.exe
 * converts its command line to the machine's ANSI code page; nothing was done
 * about the second hop, and the same conversion happens there.
 *
 * What makes it worth measuring rather than reasoning about is where the
 * non-ASCII comes from. It is not only an unusual profile name:
 * Project.getBuildDir() names the build directory after the script -
 * cache/jars/&lt;script&gt;.&lt;hash&gt; - so a script called レポート.java puts its own
 * name into the path of the jar that java is then handed. A developer naming
 * a script in their own language is the ordinary case, not the edge one.
 *
 * These run everywhere. On Linux and macOS they say what the behaviour should
 * be; on Windows they say what it is.
 */
class TestSecondHopPaths extends AbstractScriptTest {

	private static final String JAPANESE = "レポート";

	private static final Path JKITE_JAR = Paths.get(System.getProperty("jkite.jar", "build/libs/jkite.jar"));

	private Map<String, String> env(Path jkiteDir) {
		Map<String, String> env = new HashMap<>(System.getenv());
		env.put("JAVA_HOME", System.getProperty("java.home"));
		env.put("JKITE_DIR", jkiteDir.toString());
		env.remove("JKITE_CACHE_DIR");
		// nothing here declares //DEPS, so a download would be a bug, not a wait
		env.put("JKITE_CONFIRM_DOWNLOADS", "always");
		return env;
	}

	private RunResult runJkite(Path script, Path jkiteDir) throws Exception {
		assertTrue(Files.isRegularFile(JKITE_JAR),
				"the shaded jar is not built, so this is testing nothing: " + JKITE_JAR);
		return runProcess(Arrays.asList(System.getProperty("java.home") + "/bin/java",
				"-jar", JKITE_JAR.toAbsolutePath().toString(), script.toString()), env(jkiteDir));
	}

	/**
	 * A script that prints one word, so that running it is visible.
	 *
	 * The class is not public, which is what lets the file be called anything:
	 * javac insists a public class sit in a file named after it, so a script
	 * named レポート.java would have to declare a class called レポート. That is
	 * a rule of Java's and has nothing to do with what is being measured here,
	 * so it is kept out of the way.
	 */
	private Path script(Path dir, String name) throws IOException {
		Files.createDirectories(dir);
		Path file = dir.resolve(name + ".java");
		Files.write(file, ("class Report {\n"
				+ "  public static void main(String[] a) { System.out.println(\"ran\"); }\n"
				+ "}\n").getBytes(StandardCharsets.UTF_8));
		return file;
	}

	/** The baseline: everything ASCII, so a failure below is about the name. */
	@Test
	void anOrdinaryScriptRuns() throws Exception {
		Path script = script(tempDir.resolve("plain"), "Report");

		RunResult result = runJkite(script, tempDir.resolve("home"));

		assertEquals(0, result.exitCode, result.stdout + result.stderr);
		assertTrue(result.stdout.contains("ran"), result.stdout + result.stderr);
	}

	/**
	 * The ordinary case for anyone not writing in English. The script's name
	 * becomes part of the build directory, so this is the path java is handed
	 * on the second hop whatever the machine is called.
	 */
	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void aScriptNamedInJapaneseRuns() throws Exception {
		Path script = script(tempDir.resolve("plain2"), JAPANESE);

		RunResult result = runJkite(script, tempDir.resolve("home2"));

		assertEquals(0, result.exitCode,
				"a script named in Japanese did not run. Its name is part of the built jar's path, "
						+ "which is what jkite hands java on the second hop:\n"
						+ result.stdout + result.stderr);
		assertTrue(result.stdout.contains("ran"), result.stdout + result.stderr);
	}

	/** And the other way in: an ASCII script under a cache that is not. */
	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void anOrdinaryScriptUnderAJapaneseCacheRuns() throws Exception {
		Path script = script(tempDir.resolve("plain3"), "Report");

		RunResult result = runJkite(script, tempDir.resolve(JAPANESE + "-home"));

		assertEquals(0, result.exitCode,
				"a cache directory named in Japanese stopped the run:\n" + result.stdout + result.stderr);
		assertTrue(result.stdout.contains("ran"), result.stdout + result.stderr);
	}

	/**
	 * The class path separator in a path jkite is given, end to end, so that
	 * the check is known to be wired in and not only unit tested.
	 *
	 * ':' is legal in a POSIX filename and is what separates one class path
	 * entry from the next, so a JKITE_DIR of "/tmp/ho:me" used to end the run
	 * at "Could not find or load main class Report" - true, and no help. This
	 * is POSIX-only because Windows does not allow ':' in a name at all;
	 * there the same thing is ';', which the unit tests cover.
	 */
	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void aCacheDirectoryCarryingTheClassPathSeparatorIsRefusedWithAReason() throws Exception {
		Path script = script(tempDir.resolve("plain6"), "Report");

		RunResult result = runJkite(script, tempDir.resolve("ho:me"));
		String said = result.stdout + result.stderr;

		assertTrue(result.exitCode != 0, said);
		assertTrue(said.contains("class path"),
				"it did not say what was wrong with the path: " + said);
		assertTrue(!said.contains("Could not find or load main class"),
				"it still gets as far as java and fails there: " + said);
	}

	/**
	 * An ASCII filename does not save a script that lives in a directory
	 * named in Japanese, and this is here because that is the first thing
	 * anyone assumes.
	 *
	 * What javac is handed is the absolute path - Project puts the source
	 * through toAbsolutePath() before anything else - so every directory
	 * between the drive and the file is on that command line, and the ANSI
	 * code page has to hold all of it. Calling the file Report.java changes
	 * one component out of several.
	 */
	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void anAsciiScriptInAJapaneseDirectoryRuns() throws Exception {
		Path script = script(tempDir.resolve(JAPANESE + "-project"), "Report");

		RunResult result = runJkite(script, tempDir.resolve("home5"));

		assertEquals(0, result.exitCode, result.stdout + result.stderr);
		assertTrue(result.stdout.contains("ran"), result.stdout + result.stderr);
	}

	/**
	 * The same thing on Windows, measured rather than assumed, because the
	 * answer is the one people act on: an ASCII filename is not enough.
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void anAsciiFilenameDoesNotRescueAJapaneseDirectory() throws Exception {
		Path script = script(tempDir.resolve(JAPANESE + "-project"), "Report");

		RunResult result = runJkite(script, tempDir.resolve("home5"));
		String said = result.stdout + result.stderr;

		assertTrue(result.exitCode != 0,
				"an ASCII filename under a Japanese directory now works; README says it does not, "
						+ "and should be corrected: " + said);
	}

	/**
	 * What is left on Windows, and where it now stops - which is the measure
	 * of what reducing the build directory name achieved.
	 *
	 * Before, the compile died inside javac's own argument checking, on the
	 * "-d" it was handed: Arguments.checkDirectory, WindowsPath.parse, a path
	 * with question marks in it. That path was jkite's, named after the
	 * script, and it is ASCII now. The failure has moved to the only path left
	 * that is not jkite's to choose:
	 *
	 *   error: Invalid filename: C:\...\????.java
	 *
	 * That is the script the user named and asked jkite to run. Nothing here
	 * can rename it, and javac.exe converts its command line to the machine's
	 * ANSI code page whatever jkite does. So the reduction was necessary and
	 * is not sufficient: on its own it makes no failing case pass, it removes
	 * one of the two reasons.
	 *
	 * What changed since: jkite now looks at the path before starting javac
	 * and refuses it with a message that names the path, the encoding and
	 * what to do. So the failure is still the script's own name - that part
	 * is unchanged and unchangeable - but it is now jkite saying so rather
	 * than javac's "Invalid filename". This asserts that, rather than just
	 * the failure, so that if jkite ever starts putting a bad path of its
	 * own back, this test fails for a different reason and says which.
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void whatIsLeftOnWindowsIsTheScriptsOwnPath() throws Exception {
		Path script = script(tempDir.resolve("plain4"), JAPANESE);

		RunResult result = runJkite(script, tempDir.resolve("home4"));
		String said = result.stdout + result.stderr;

		assertTrue(result.exitCode != 0,
				"javac now takes a source path outside the code page; this test and the note in "
						+ "README can go: " + said);
		assertTrue(said.contains("The path of the script"),
				"it fails, but not by naming the script - so something else is handing javac a "
						+ "bad path again: " + said);
		assertTrue(said.contains("windows-1252") || said.contains("windows-"),
				"it did not say which encoding could not hold it: " + said);
		assertTrue(!said.contains("Invalid filename") && !said.contains("checkDirectory"),
				"it still gets as far as javac, so the check did not look at this path: " + said);
	}

	/**
	 * The same divergence, end to end: does jkite run the file a shell would
	 * open?
	 *
	 * real/link points at elsewhere, so "real/link/../Report.java" walks into
	 * elsewhere and back out, landing on the one at the top - while folding
	 * the string stays inside real and finds the other. Each prints its own
	 * word, so what comes out says which file was compiled, rather than which
	 * spelling of a path jkite settled on.
	 *
	 * Symbolic links on Windows need administrator rights or developer mode,
	 * which a runner does not have, so this is the POSIX statement of it. The
	 * unit test beside RealPath is the same measurement without the process.
	 */
	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void aLinkFollowedByDotDotRunsTheFileTheShellWouldOpen() throws Exception {
		Path root = tempDir.resolve("links");
		Files.createDirectories(root.resolve("elsewhere"));
		say(root.resolve("real"), "folded");
		say(root, "walked");
		Files.createSymbolicLink(root.resolve("real/link"), root.resolve("elsewhere"));

		RunResult result = runJkite(root.resolve("real/link/../Report.java"), tempDir.resolve("home-links"));
		String said = result.stdout + result.stderr;

		assertEquals(0, result.exitCode, said);
		assertTrue(said.contains("walked"), "it compiled the other file: " + said);
	}

	/** A script that prints which of the two copies of itself it is. */
	private void say(Path dir, String word) throws IOException {
		Files.createDirectories(dir);
		Files.write(dir.resolve("Report.java"), ("class Report {\n"
				+ "  public static void main(String[] a) { System.out.println(\"" + word + "\"); }\n"
				+ "}\n").getBytes(StandardCharsets.UTF_8));
	}
}
