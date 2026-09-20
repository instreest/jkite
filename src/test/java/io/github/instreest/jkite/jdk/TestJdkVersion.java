package io.github.instreest.jkite.jdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading a JDK's version, from the 'release' file it ships with down to the
 * one number everything is decided on.
 *
 * That number decides which JDK a script runs on and whether a jar built
 * earlier can be reused, and getting it wrong is quiet both ways: too low and
 * a JDK that would have done is passed over for a 190 MB download, too high
 * and a jar is reused that the JVM of the day cannot load.
 */
class TestJdkVersion {

	@TempDir
	Path dir;

	/** What the JDKs a user actually has say about themselves. */
	@Test
	void theVersionsRealJdksReportAreReadAsTheirMajor() {
		assertEquals(21, Jdk.parseJavaVersion("21.0.10"));
		assertEquals(21, Jdk.parseJavaVersion("21.0.12+7"));
		assertEquals(25, Jdk.parseJavaVersion("25.0.3+9"));
		assertEquals(17, Jdk.parseJavaVersion("17"));
		assertEquals(11, Jdk.parseJavaVersion("11.0.2"));
	}

	/**
	 * Java 8 and older call themselves 1.8, and the 8 is the second number.
	 * A JDK read as 1 is older than everything and would never be chosen.
	 */
	@Test
	void theOldOnePointEightSpellingIsReadAsEight() {
		assertEquals(8, Jdk.parseJavaVersion("1.8.0_292"));
		assertEquals(8, Jdk.parseJavaVersion("1.8.0_292-b10"));
		assertEquals(8, Jdk.parseJavaVersion("1.8"));
	}

	@Test
	void aVendorsExtraNumbersAndLabelsDoNotGetInTheWay() {
		// what the JVM running these tests calls itself in a stack trace
		assertEquals(21, Jdk.parseJavaVersion("21.0.12.1"));
		assertEquals(21, Jdk.parseJavaVersion("21-ea"));
		assertEquals(21, Jdk.parseJavaVersion("21.0.10+7-LTS"));
		// before a release has a patch number the build number follows the
		// major directly, and the + has to separate them like any other
		assertEquals(21, Jdk.parseJavaVersion("21+35"));
	}

	/** Nothing readable means nothing claimed: 0 is older than any JDK asked for. */
	@Test
	void somethingThatIsNotAVersionIsNoVersion() {
		assertEquals(0, Jdk.parseJavaVersion(null));
		assertEquals(0, Jdk.parseJavaVersion(""));
		assertEquals(0, Jdk.parseJavaVersion("GraalVM"));
	}

	/**
	 * There are two of these, and they have to agree. This one reads the JDK
	 * that will run the script; the one in the Util shim reads the Build-Jdk of
	 * a jar built earlier, and AppBuilder compares the two numbers to decide
	 * whether that jar can be reused. A difference between them is a decision
	 * made on two different readings of the same kind of string.
	 */
	@Test
	void theOtherReadingOfAVersionAgreesWithThisOne() {
		List<String> versions = Arrays.asList(
				"21.0.10", "21.0.12+7", "25.0.3+9", "17", "11.0.2",
				"1.8.0_292", "1.8.0_292-b10", "1.8",
				"21.0.12.1", "21-ea", "21.0.10+7-LTS", "21+35",
				null, "", "GraalVM");

		for (String version : versions) {
			assertEquals(dev.jbang.util.JavaUtil.parseJavaVersion(version), Jdk.parseJavaVersion(version),
					"the two readings of " + version + " differ");
		}
	}

	// ------------------------------------------------- where the string is read

	@Test
	void theVersionIsTakenFromTheQuotesJavaPrintsItIn() {
		assertEquals("21.0.10", Jdk.parseJavaOutput("openjdk version \"21.0.10\" 2026-01-20"));
		assertEquals("1.8.0_292", Jdk.parseJavaOutput("java version \"1.8.0_292\""));
		assertNull(Jdk.parseJavaOutput("no quotes here"));
		assertNull(Jdk.parseJavaOutput(null));
	}

	@Test
	void aJdkIsAskedItsVersionThroughTheReleaseFileItShipsWith() throws IOException {
		release("JAVA_VERSION=\"21.0.10\"", "OS_NAME=\"Linux\"");

		assertEquals(Optional.of("21.0.10"), Jdk.readVersionFromReleaseFile(dir));
	}

	/** Some JDKs say it only the long way. */
	@Test
	void theRuntimeVersionWillDoWhenThereIsNoOtherOne() throws IOException {
		release("OS_NAME=\"Linux\"", "JAVA_RUNTIME_VERSION=\"21.0.10+7-LTS\"");

		assertEquals(21, Jdk.parseJavaVersion(Jdk.readVersionFromReleaseFile(dir).orElse(null)));
	}

	@Test
	void aDirectoryWithNoReleaseFileClaimsNothing() {
		assertFalse(Jdk.readVersionFromReleaseFile(dir).isPresent());
	}

	@Test
	void aReleaseFileThatSaysNothingAboutTheVersionClaimsNothing() throws IOException {
		release("OS_NAME=\"Linux\"", "OS_ARCH=\"x86_64\"");

		assertFalse(Jdk.readVersionFromReleaseFile(dir).isPresent());
	}

	private void release(String... lines) throws IOException {
		Files.write(dir.resolve("release"), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
	}
}
