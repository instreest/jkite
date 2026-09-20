package io.github.instreest.jkite.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

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
 * What a subst drive and a junction do to a path on the way to javac.
 *
 * This exists because of a question with a practical answer behind it: if a
 * developer's real project directory is named in Japanese, and they work
 * through "subst X: C:\...\プロジェクト" so that everything they type is
 * X:\..., does jkite work?
 *
 * It matters twice over. Today jkite calls toAbsolutePath().normalize() on
 * the source, and normalize() is string folding - it does not ask the file
 * system anything - so the hypothesis is that X:\Report.java stays
 * X:\Report.java and javac never sees a Japanese character. If that holds,
 * subst is a real workaround for the ANSI code page problem, and it is one a
 * user can apply without changing any machine-wide setting.
 *
 * It also decides a change that was being considered. Replacing normalize()
 * with toRealPath() would make jkite run the same file a shell would when
 * symlinks and ".." are mixed. But toRealPath() asks the file system, and if
 * the file system answers "X:\ is really C:\...\プロジェクト", then that
 * change quietly takes the workaround away from exactly the people who need
 * it. So the second test measures what toRealPath() does to these paths,
 * separately from whether jkite works, because that is the fact the decision
 * turns on.
 *
 * Windows only: subst and junctions are Windows. Neither needs administrator
 * rights, unlike a symlink, which is why these two and not mklink.
 */
class TestSubstAndJunction extends AbstractScriptTest {

	private static final String JAPANESE = "\u30d7\u30ed\u30b8\u30a7\u30af\u30c8";

	private static final Path JKITE_JAR = Paths.get(System.getProperty("jkite.jar", "build/libs/jkite.jar"));

	private Map<String, String> env(Path jkiteDir) {
		Map<String, String> env = new HashMap<>(System.getenv());
		env.put("JAVA_HOME", System.getProperty("java.home"));
		env.put("JKITE_DIR", jkiteDir.toString());
		env.remove("JKITE_CACHE_DIR");
		env.put("JKITE_CONFIRM_DOWNLOADS", "always");
		return env;
	}

	private RunResult runJkite(String script, Path jkiteDir) throws Exception {
		assertTrue(Files.isRegularFile(JKITE_JAR),
				"the shaded jar is not built, so this is testing nothing: " + JKITE_JAR);
		return runProcess(Arrays.asList(System.getProperty("java.home") + "/bin/java",
				"-jar", JKITE_JAR.toAbsolutePath().toString(), script), env(jkiteDir));
	}

	/** An ASCII script, so that any non-ASCII on the command line is the directory's. */
	private void script(Path dir) throws IOException {
		Files.createDirectories(dir);
		Files.write(dir.resolve("Report.java"), ("class Report {\n"
				+ "  public static void main(String[] a) { System.out.println(\"ran\"); }\n"
				+ "}\n").getBytes(StandardCharsets.UTF_8));
	}

	private RunResult cmd(String line) throws Exception {
		return runProcess(Arrays.asList("cmd.exe", "/c", line), new HashMap<>(System.getenv()));
	}

	/**
	 * Maps a free drive letter to dir and returns it as "X:", or aborts if
	 * subst is not usable here - a runner without a free letter is not a
	 * failure of jkite's.
	 */
	private String subst(Path dir) throws Exception {
		for (char letter = 'Z'; letter >= 'S'; letter--) {
			if (Files.exists(Paths.get(letter + ":\\"))) {
				continue;
			}
			RunResult made = cmd("subst " + letter + ": \"" + dir + "\"");
			if (made.exitCode == 0) {
				return letter + ":";
			}
		}
		return abort("no drive letter could be substed here");
	}

	/**
	 * The question as a user would ask it: real directory in Japanese, subst
	 * drive in ASCII, script named in ASCII. Does it run?
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void aSubstDriveHidesAJapaneseDirectoryFromJavac() throws Exception {
		Path real = tempDir.resolve(JAPANESE + "-subst");
		script(real);
		String drive = subst(real);
		try {
			RunResult result = runJkite(drive + "\\Report.java", tempDir.resolve("home-subst"));
			String said = result.stdout + result.stderr;

			assertEquals(0, result.exitCode, said);
			assertTrue(said.contains("ran"), said);
		} finally {
			cmd("subst " + drive + " /d");
		}
	}

	/**
	 * A junction is NOT the same, and this is what says so.
	 *
	 * It used to pass. Then jkite started resolving sources to the file the
	 * shell would open, so that a link followed by ".." does not silently
	 * name a different file - and toRealPath(), which is how that is done,
	 * follows a junction, because a junction is a reparse point in the file
	 * system and that is exactly what it is defined to follow. The Japanese
	 * directory comes back, and the path check refuses it.
	 *
	 * That is a real cost of that change and it is recorded rather than
	 * papered over: somebody who had worked around the code page with a
	 * junction loses that. subst is not affected - it is a drive-letter
	 * mapping rather than a reparse point, so toRealPath() does not see
	 * through it - which is why subst and not a junction is what README
	 * tells people to use.
	 *
	 * It refuses with a reason, which is the part that keeps this tolerable.
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void aJunctionNoLongerHidesAJapaneseDirectory() throws Exception {
		Path real = tempDir.resolve(JAPANESE + "-junction");
		script(real);
		Path link = tempDir.resolve("ascii-link");
		RunResult made = cmd("mklink /J \"" + link + "\" \"" + real + "\"");
		if (made.exitCode != 0) {
			abort("mklink /J is not usable here: " + made.stdout + made.stderr);
		}

		RunResult result = runJkite(link + "\\Report.java", tempDir.resolve("home-junction"));
		String said = result.stdout + result.stderr;

		assertTrue(result.exitCode != 0, said);
		assertTrue(said.contains("The path of the script"),
				"it failed, but not by naming the path it could not use: " + said);
		// "-junction" and not the Japanese itself: the message travels back
		// through the console's code page, which by construction cannot hold
		// those characters, so they arrive as "??????". The suffix is on the
		// real directory and not on the link, which was called "ascii-link",
		// so its presence is what says the junction was resolved.
		assertTrue(said.contains("-junction"),
				"it did not show the real directory, so the message does not explain itself: "
						+ said);
	}

	/**
	 * The fact the toRealPath() decision turns on, measured on its own, for a
	 * subst drive.
	 *
	 * Measured answer: toRealPath() leaves Z:\\Report.java as
	 * Z:\\Report.java. A subst drive is a per-session drive-letter mapping
	 * held by the object manager, not a reparse point in the file system, so
	 * asking the file system for the real path does not see through it. I
	 * expected the opposite and was wrong.
	 *
	 * So switching Project from normalize() to toRealPath() would not take
	 * the subst workaround away, and this test is what would say so if a
	 * future Windows or JDK changed its mind.
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void toRealPathLeavesASubstDriveAlone() throws Exception {
		Path real = tempDir.resolve(JAPANESE + "-real-subst");
		script(real);
		String drive = subst(real);
		try {
			Path resolved = Paths.get(drive + "\\Report.java").toRealPath();

			assertTrue(!resolved.toString().contains(JAPANESE),
					"toRealPath now sees through subst, so it would take the workaround away: "
							+ resolved);
		} finally {
			cmd("subst " + drive + " /d");
		}
	}

	/**
	 * The same for a junction, which is a different mechanism and may well
	 * answer differently: a junction IS a reparse point in the file system,
	 * which is exactly what toRealPath() is defined to follow.
	 *
	 * Whichever way it comes out, it is worth having written down, because
	 * "use a junction" and "use subst" are advice that would otherwise look
	 * interchangeable.
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void toRealPathFollowsAJunction() throws Exception {
		Path real = tempDir.resolve(JAPANESE + "-real-junction");
		script(real);
		Path link = tempDir.resolve("ascii-link-real");
		RunResult made = cmd("mklink /J \"" + link + "\" \"" + real + "\"");
		if (made.exitCode != 0) {
			abort("mklink /J is not usable here: " + made.stdout + made.stderr);
		}

		Path resolved = link.resolve("Report.java").toRealPath();

		assertTrue(resolved.toString().contains(JAPANESE),
				"a junction survives toRealPath too, so it is as good a workaround as subst: "
						+ resolved);
	}
}
