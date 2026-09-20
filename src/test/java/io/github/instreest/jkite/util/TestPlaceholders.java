package io.github.instreest.jkite.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import dev.jbang.ExitException;

/** The ${...} placeholders a directive may use. */
class TestPlaceholders {

	private final Properties props = new Properties();

	TestPlaceholders() {
		props.setProperty("version", "1.2.3");
		props.setProperty("group", "org.example");
		props.setProperty("empty", "");
	}

	private String replace(String text) {
		return Placeholders.replace(text, props);
	}

	@Test
	void textWithoutAPlaceholderIsReturnedAsItIs() {
		assertEquals("org.example:thing:1.0", replace("org.example:thing:1.0"));
		assertEquals("", replace(""));
		assertNull(replace(null));
	}

	@Test
	void aPropertyIsSubstituted() {
		assertEquals("org.example:thing:1.2.3", replace("${group}:thing:${version}"));
		assertEquals("", replace("${empty}"));
	}

	@Test
	void theFirstNameThatIsSetWins() {
		assertEquals("1.2.3", replace("${absent,version}"));
		assertEquals("1.2.3", replace("${version,group}"));
	}

	@Test
	void theFallbackIsUsedWhenNothingIsSet() {
		assertEquals("9.9", replace("${absent:9.9}"));
		assertEquals("", replace("${absent:}"));
		assertEquals("1.2.3", replace("${version:9.9}"));
		assertEquals("9.9", replace("${absent,alsoAbsent:9.9}"));
	}

	@Test
	void separatorsHaveTheirOwnNames() {
		assertEquals(File.separator, replace("${/}"));
		assertEquals(File.pathSeparator, replace("${:}"));
		assertEquals("a" + File.separator + "b", replace("a${/}b"));
	}

	@Test
	void anEnvironmentVariableNeedsTheEnvPrefix() {
		String name = System.getenv().keySet().stream().findFirst().orElse(null);
		if (name != null) {
			assertEquals(System.getenv(name), replace("${env." + name + "}"));
		}
		assertEquals("none", replace("${env.JKITE_SURELY_NOT_SET:none}"));
	}

	@Test
	void aDoubledDollarIsALiteralOne() {
		assertEquals("$", replace("$$"));
		assertEquals("${version}", replace("$${version}"));
	}

	@Test
	void aLoneDollarIsKept() {
		assertEquals("100$", replace("100$"));
		assertEquals("a$b", replace("a$b"));
	}

	/**
	 * And it is the script author's mistake, not jkite's, so it exits the way
	 * every other bad input does. It used to be an IllegalStateException,
	 * which Main reports as a generic error - exit 1, where a missing script
	 * or an unknown option exits 2. A directive nobody can expand is the same
	 * kind of thing as those.
	 */
	@Test
	void aPlaceholderNothingResolvesIsInvalidInput() {
		ExitException e = assertThrows(ExitException.class, () -> replace("${absent}"));
		assertTrue(e.getMessage().contains("absent"), e.getMessage());
		assertEquals(ExitException.EXIT_INVALID_INPUT, e.getStatus(), e.getMessage());
	}

	@Test
	void aPlaceholderThatIsNeverClosedIsInvalidInputToo() {
		ExitException e = assertThrows(ExitException.class, () -> replace("${version"));

		assertEquals(ExitException.EXIT_INVALID_INPUT, e.getStatus(), e.getMessage());
		assertTrue(e.getMessage().contains("Unterminated"), e.getMessage());
	}

	/**
	 * The two limits the javadoc names, pinned so that they are a decision
	 * rather than a surprise. Neither is what upstream does differently; both
	 * are what a reader would otherwise have to find out by being caught.
	 */
	@Test
	void aPlaceholderEndsAtTheFirstClosingBrace() {
		// Someone writing "${nothing:a}b}" meaning a fallback of "a}b" gets
		// "ab}": the placeholder closed at the first brace, its fallback was
		// "a", and "b}" is what was left over as text.
		assertEquals("ab}", replace("${nothing:a}b}"));
	}

	@Test
	void aValueIsNotLookedAtAgain() {
		Properties p = new Properties();
		p.setProperty("outer", "${inner}");
		p.setProperty("inner", "reached");

		assertEquals("${inner}", Placeholders.replace("${outer}", p));
	}
}
