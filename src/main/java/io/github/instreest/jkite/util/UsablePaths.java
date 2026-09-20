package io.github.instreest.jkite.util;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Path;

import dev.jbang.ExitException;
import dev.jbang.util.Util;
import io.github.instreest.jkite.Settings;

/**
 * Whether a path can survive being handed to javac and java.
 *
 * jkite compiles and runs by starting those two as processes, so every path
 * it works with leaves through a command line. Two things can happen to a path
 * on the way, and neither of them announces itself:
 *
 * <ul>
 * <li>On Windows, java.exe and javac.exe convert their command line to the
 * machine's ANSI code page. A character that page cannot hold arrives as a
 * question mark - which a Windows path may not contain at all - and the run
 * ends at "error: Invalid filename", naming a path with question marks in it
 * that nobody wrote.</li>
 * <li>A class path is one string with its entries joined by ':' on POSIX and
 * ';' on Windows, and both of those are legal in a filename on the platform
 * that uses them. A JKITE_DIR of "/tmp/ho:me" splits the class path in half,
 * and the run ends at "Could not find or load main class", which is true and
 * says nothing about why.</li>
 * </ul>
 *
 * Both were measured rather than reasoned about. Neither message points at the
 * path, so this looks before jkite starts anything and says which path, which
 * character, and what to do instead.
 */
public final class UsablePaths {

	private UsablePaths() {
	}

	/**
	 * Refuses a path that javac or java would not receive intact.
	 *
	 * @param what what this path is, for the message: "the script", "the cache
	 *             directory (JKITE_DIR)"
	 */
	public static void requireUsable(Path path, String what) {
		check(path.toString(), what, nativeEncoding(), null);
	}

	/**
	 * The same, for a path that also goes into a class path - so it may not
	 * carry the character that separates them.
	 */
	public static void requireClassPathEntry(Path path, String what) {
		check(path.toString(), what, nativeEncoding(), Settings.CP_SEPARATOR);
	}

	/**
	 * @param encoding  what a command line can carry here, or null to accept
	 *                  anything
	 * @param separator the class path separator, or null when this path does
	 *                  not go into one
	 */
	static void check(String path, String what, Charset encoding, String separator) {
		if (separator != null && path.contains(separator)) {
			throw new ExitException(ExitException.EXIT_INVALID_INPUT,
					"The path of " + what + " contains '" + separator + "', which is what separates"
							+ " one class path entry from the next on this platform, so java would"
							+ " read it as two paths and find neither: " + path
							+ ". Move it somewhere without a '" + separator + "' in the name.");
		}
		if (encoding != null && !encoding.newEncoder().canEncode(path)) {
			throw new ExitException(ExitException.EXIT_INVALID_INPUT,
					"The path of " + what + " has characters this machine cannot put on a command"
							+ " line: " + path + ". java and javac convert theirs to " + encoding.name()
							+ ", so those characters would reach them as question marks. Rename it"
							+ " using characters " + encoding.name() + " has, or run where a code"
							+ " page that has them is in use.");
		}
	}

	/**
	 * What a command line can carry, as sun.jnu.encoding reports it - the
	 * property the JVM itself uses for file names, which on Windows follows
	 * the machine's ANSI code page and is not the same as file.encoding (UTF-8
	 * since Java 18, and no help here).
	 *
	 * Null when it cannot be read as a charset, which leaves the check off
	 * rather than turning an unrecognised property into a refusal.
	 */
	static Charset nativeEncoding() {
		String name = System.getProperty("sun.jnu.encoding");
		if (name == null || name.trim().isEmpty()) {
			return null;
		}
		try {
			return Charset.forName(name.trim());
		} catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
			Util.verboseMsg("Not checking paths against sun.jnu.encoding=" + name + ": " + e);
			return null;
		}
	}
}
