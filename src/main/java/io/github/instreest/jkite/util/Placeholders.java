package io.github.instreest.jkite.util;

import java.io.File;
import java.util.Properties;

import dev.jbang.ExitException;

/**
 * Expands the <code>${...}</code> placeholders JBang allows in a directive.
 *
 * <pre>
 * ${name}              a property, or an environment variable as ${env.NAME}
 * ${a,b}               the first of them that is set
 * ${a:fallback}        the text after the colon when none of them is
 * ${/} ${:}            the file and path separator of this platform
 * $$                   a literal dollar sign
 * </pre>
 *
 * A placeholder that resolves to nothing and has no fallback is an error: a
 * directive naming a property nobody set would otherwise build something
 * subtly different from what the script asked for. So is one that is never
 * closed. Both are the script author's mistake rather than jkite's, and are
 * reported as invalid input.
 *
 * Two limits, neither of which upstream has either, written down because they
 * are the kind that is found by being surprised: a placeholder ends at the
 * first <code>}</code>, so a fallback cannot contain one, and a value is not
 * looked at again, so <code>${a}</code> whose value is <code>${b}</code>
 * expands to the text <code>${b}</code>.
 *
 * This is jkite's own implementation of the behaviour upstream's
 * PropertiesValueResolver has, written so that the fork carries no code whose
 * licence history is unclear.
 */
public final class Placeholders {

	private Placeholders() {
	}

	/** Expands the placeholders in {@code text} against {@code properties}. */
	public static String replace(String text, Properties properties) {
		if (text == null || text.indexOf('$') < 0) {
			return text;
		}
		StringBuilder out = new StringBuilder(text.length());
		int i = 0;
		while (i < text.length()) {
			char c = text.charAt(i);
			if (c != '$') {
				out.append(c);
				i++;
			} else if (i + 1 < text.length() && text.charAt(i + 1) == '$') {
				out.append('$');
				i += 2;
			} else if (i + 1 < text.length() && text.charAt(i + 1) == '{') {
				int close = text.indexOf('}', i + 2);
				if (close < 0) {
					throw new ExitException(ExitException.EXIT_INVALID_INPUT,
							"Unterminated ${...} in: " + text);
				}
				out.append(expand(text.substring(i + 2, close), text, properties));
				i = close + 1;
			} else {
				out.append(c);
				i++;
			}
		}
		return out.toString();
	}

	/** The value of one placeholder, without its {@code ${} and }}. */
	private static String expand(String expression, String whole, Properties properties) {
		String names = expression;
		String fallback = null;
		int colon = expression.indexOf(':');
		// "${:}" is the path separator, not an empty name with an empty fallback
		if (colon > 0) {
			names = expression.substring(0, colon);
			fallback = expression.substring(colon + 1);
		}
		for (String name : names.split(",", -1)) {
			String value = lookup(name.trim(), properties);
			if (value != null) {
				return value;
			}
		}
		if (fallback != null) {
			return fallback;
		}
		throw new ExitException(ExitException.EXIT_INVALID_INPUT,
				"Nothing is set for ${" + expression + "} in: " + whole);
	}

	private static String lookup(String name, Properties properties) {
		if ("/".equals(name)) {
			return File.separator;
		}
		if (":".equals(name)) {
			return File.pathSeparator;
		}
		String value = properties.getProperty(name);
		if (value == null && name.startsWith("env.")) {
			value = System.getenv(name.substring("env.".length()));
		}
		return value;
	}
}
