package io.github.instreest.jkite.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the build directory is called.
 *
 * jkite compiles into cache/jars/&lt;script&gt;.&lt;hash&gt; and hands that path to
 * javac, and the jar it writes there to java. Both are launchers that convert
 * their command line to the machine's ANSI code page on Windows, so a name
 * outside that page arrives as question marks - which a Windows path may not
 * contain at all - and the run ends at "Error during compile". A script called
 * レポート.java on an English Windows is enough to do it, measured on a runner
 * in code page 437.
 *
 * The name is there to be read. The hash beside it is what identifies the
 * directory, and it covers the source bytes, so reducing the name costs
 * nothing.
 */
class TestBuildPathName {

	@TempDir
	Path dir;

	/**
	 * The property that decides whether this can be shipped without anyone
	 * noticing: a name that works today comes out byte for byte the same, so
	 * no build directory is renamed and nothing is rebuilt.
	 */
	@Test
	void anameThatAlreadyWorksIsUntouched() {
		for (String name : new String[] { "Report.java", "report-2.java", "my_report.java",
				"Report v2.java", "a.b.c.java", "~report.java", "(draft).java", "100%.java",
				"report'.java", "report,report.java", "+x=y.java", "@home.java", "#1.java" }) {
			assertEquals(name, Project.inAscii(name), name + " was changed and did not need to be");
		}
	}

	@Test
	void whatAWindowsPathCannotHoldIsReplaced() {
		assertEquals("____.java", Project.inAscii("レポート.java"));
		assertEquals("______.java", Project.inAscii("Проект.java"));
		assertEquals("caf_.java", Project.inAscii("café.java"));
	}

	/** Windows refuses these outright, whatever the code page is. */
	@Test
	void theCharactersWindowsReservesAreReplacedToo() {
		assertEquals("a_b_c_d_e_f_g_h_i", Project.inAscii("a<b>c:d\"e/f\\g|h?i"));
		assertEquals("_", Project.inAscii("*"));
	}

	/** A surrogate pair is two chars, and becomes two underscores, not one. */
	@Test
	void aCharacterOutsideTheBasicPlaneIsReplaced() {
		assertEquals("tools__.java", Project.inAscii("tools🛠.java"));
	}

	// -------------------------------------------------------------------------
	// and the paths themselves
	// -------------------------------------------------------------------------

	private Project projectNamed(String fileName) throws IOException {
		Path script = dir.resolve(fileName);
		Files.write(script, "class Report { public static void main(String... a) {} }\n"
			.getBytes(StandardCharsets.UTF_8));
		return new Project(script, Collections.emptyMap());
	}

	@Test
	void theBuildDirectoryAndJarAreAsciiEvenWhenTheScriptIsNot() throws IOException {
		Project project = projectNamed("レポート.java");

		String buildDir = project.getBuildDir().getFileName().toString();
		String jar = project.getJarFile().getFileName().toString();

		assertTrue(buildDir.startsWith("____.java."), buildDir);
		assertEquals("____.jar", jar);
		assertTrue(isAscii(buildDir) && isAscii(jar), buildDir + " / " + jar);
	}

	/**
	 * Two scripts whose names reduce to the same thing still get a directory
	 * each: what identifies it is the hash, and that covers the source.
	 */
	@Test
	void twoScriptsThatReduceAlikeAreStillToldApart() throws IOException {
		Path a = dir.resolve("a");
		Path b = dir.resolve("b");
		Files.createDirectories(a);
		Files.createDirectories(b);
		Files.write(a.resolve("レポート.java"),
				"class A { public static void main(String... x) {} }\n".getBytes(StandardCharsets.UTF_8));
		Files.write(b.resolve("Прое.java"),
				"class B { public static void main(String... x) {} }\n".getBytes(StandardCharsets.UTF_8));

		Path first = new Project(a.resolve("レポート.java"), Collections.emptyMap()).getBuildDir();
		Path second = new Project(b.resolve("Прое.java"), Collections.emptyMap()).getBuildDir();

		assertNotEquals(first, second, "two different scripts share a build directory: " + first);
	}

	private static boolean isAscii(String s) {
		return s.chars().allMatch(c -> c >= 0x20 && c < 0x7f);
	}
}
