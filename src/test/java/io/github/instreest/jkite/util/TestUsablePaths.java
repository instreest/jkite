package io.github.instreest.jkite.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;

import org.junit.jupiter.api.Test;

import dev.jbang.ExitException;

/**
 * The two ways a path can be lost between jkite and the JDK tools it starts,
 * both of which were found by measurement and neither of which says anything
 * useful when it happens.
 *
 * The charsets are passed in rather than read from the machine, because the
 * whole point is what happens on a machine that is not this one: a Windows in
 * code page 1252 or 932. Running the check against those here is what makes
 * it testable at all.
 */
class TestUsablePaths {

	private static final Charset WESTERN = Charset.forName("windows-1252");
	private static final Charset JAPANESE = Charset.forName("MS932");
	private static final Charset UTF8 = Charset.forName("UTF-8");

	private static ExitException refused(String path, Charset encoding, String separator) {
		return assertThrows(ExitException.class,
				() -> UsablePaths.check(path, "the script", encoding, separator));
	}

	// -------------------------------------------------------------------------
	// the class path separator, which is a legal filename character
	// -------------------------------------------------------------------------

	/**
	 * ':' on POSIX and ';' on Windows, and each is legal in a filename on the
	 * platform that uses it. Measured: a JKITE_DIR of "/tmp/ho:me" ends the
	 * run at "Could not find or load main class", which is true and explains
	 * nothing.
	 */
	@Test
	void aPathCarryingTheSeparatorIsRefused() {
		ExitException e = refused("/tmp/ho:me/cache/x.jar", UTF8, ":");

		assertEquals(ExitException.EXIT_INVALID_INPUT, e.getStatus());
		assertTrue(e.getMessage().contains("/tmp/ho:me/cache/x.jar"), e.getMessage());
		assertTrue(e.getMessage().contains("class path"), e.getMessage());
	}

	@Test
	void theWindowsSeparatorIsTheWindowsOne() {
		refused("C:\\a;b\\x.jar", UTF8, ";");
		// and a drive letter's colon is not a separator there
		UsablePaths.check("C:\\a\\x.jar", "the script", UTF8, ";");
	}

	/**
	 * A source file is an argument of its own, never joined into a list, so a
	 * separator in it reaches javac intact. Measured: a project directory
	 * called "pr:oj" builds and runs. Refusing it would be refusing something
	 * that works.
	 */
	@Test
	void aPathThatIsNotAClassPathEntryMayCarryIt() {
		UsablePaths.check("/tmp/pr:oj/Report.java", "the script", UTF8, null);
	}

	// -------------------------------------------------------------------------
	// what a command line can carry on the machine it runs on
	// -------------------------------------------------------------------------

	@Test
	void aPathTheCodePageCannotHoldIsRefused() {
		ExitException e = refused("C:\\work\\\u30ec\u30dd\u30fc\u30c8.java", WESTERN, null);

		assertEquals(ExitException.EXIT_INVALID_INPUT, e.getStatus());
		assertTrue(e.getMessage().contains("windows-1252"),
				"it should name the encoding, so the reader can tell what would fit: " + e.getMessage());
	}

	/**
	 * The asymmetry that makes this worth asking the machine rather than
	 * guessing: a Japanese Windows holds Japanese and not an accent, and a
	 * Western one holds the accent and not the Japanese.
	 */
	@Test
	void whatFitsDependsOnTheMachine() {
		UsablePaths.check("C:\\work\\\u30ec\u30dd\u30fc\u30c8.java", "the script", JAPANESE, null);
		refused("C:\\work\\\u30ec\u30dd\u30fc\u30c8.java", WESTERN, null);

		UsablePaths.check("C:\\work\\caf\u00e9.java", "the script", WESTERN, null);
		refused("C:\\work\\caf\u00e9.java", JAPANESE, null);
	}

	/**
	 * And which "café" it is matters, which I got wrong writing the test
	 * above and the charset corrected.
	 *
	 * windows-1252 has é as one character, U+00E9. It does not have "e"
	 * followed by a combining acute, which is the same word and a different
	 * string - and it is the form macOS stores filenames in. So a folder
	 * called café made on a Mac and copied to a Western Windows is refused
	 * where one typed on that Windows is not.
	 */
	@Test
	void aComposedAccentFitsWhereADecomposedOneDoesNot() {
		UsablePaths.check("C:\\work\\caf\u00e9.java", "the script", WESTERN, null);
		refused("C:\\work\\cafe\u0301.java", WESTERN, null);
	}

	@Test
	void utf8HoldsEverythingSoNothingIsRefused() {
		UsablePaths.check("/home/\u7530\u4e2d/\u30ec\u30dd\u30fc\u30c8.java", "the script", UTF8, ":");
	}

	/**
	 * An encoding that cannot be read leaves the check off rather than
	 * refusing every path on a machine whose property jkite does not
	 * recognise.
	 */
	@Test
	void anUnknownEncodingIsNotAReasonToRefuse() {
		UsablePaths.check("C:\\work\\\u30ec\u30dd\u30fc\u30c8.java", "the script", null, null);
	}

	/**
	 * And a property that is not a charset name leaves it off rather than
	 * making every path on that machine a refusal. Set here rather than
	 * hoped for: this machine's is perfectly readable, so nothing would ever
	 * reach that branch on its own.
	 */
	@Test
	void anUnreadableEncodingPropertyTurnsTheCheckOff() {
		String was = System.getProperty("sun.jnu.encoding");
		try {
			System.setProperty("sun.jnu.encoding", "not a charset at all");
			assertNull(UsablePaths.nativeEncoding());
			System.setProperty("sun.jnu.encoding", "x-no-such-charset");
			assertNull(UsablePaths.nativeEncoding());
		} finally {
			if (was == null) {
				System.clearProperty("sun.jnu.encoding");
			} else {
				System.setProperty("sun.jnu.encoding", was);
			}
		}
	}

	@Test
	void theRealMachinesEncodingIsReadable() {
		Charset here = UsablePaths.nativeEncoding();

		assertTrue(here == null || here.canEncode(), "sun.jnu.encoding is not usable: " + here);
	}
}
