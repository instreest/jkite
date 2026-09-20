package io.github.instreest.jkite.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import dev.jbang.ExitException;

/**
 * A Java version as requested by <code>//JAVA</code>: a major version
 * (<code>17</code>) or a major version and anything later (<code>17+</code>).
 * Those are the two forms a script can actually ask for. jkite has no
 * <code>--java</code> option - what a tool needs is the tool author's to
 * declare, not its user's to override - and <code>//JAVA</code> is read by
 * upstream's parser, which accepts a number and an optional plus sign and
 * nothing else.
 *
 * {@link #parse} nevertheless understands a full version such as
 * <code>25.0.3</code>, and a request without a plus sign matches any version
 * that begins with the requested components: <code>17</code> matches
 * <code>17.0.9+9</code>, and <code>25.0.3</code> would match
 * <code>25.0.3+9</code> but not <code>25.0.30</code>. Nothing in jkite
 * produces such a request today - the directive is rejected before it gets
 * here - so this is not a way to pin an exact JDK, whatever the shape of the
 * code suggests. It is kept because the other half of the comparison, the
 * concrete version read from a JDK's release file or from the index, is a full
 * version and is parsed by the same components.
 */
public final class RequestedVersion implements Comparable<RequestedVersion> {
	private static final Pattern SYNTAX = Pattern.compile("\\d+(\\.\\d+)*\\+?");

	private final String text;
	private final int[] parts;
	private final boolean open;

	private RequestedVersion(String text, int[] parts, boolean open) {
		this.text = text;
		this.parts = parts;
		this.open = open;
	}

	public static boolean isValid(String version) {
		return version != null && SYNTAX.matcher(version.trim()).matches();
	}

	public static RequestedVersion parse(String version) {
		if (!isValid(version)) {
			throw new ExitException(ExitException.EXIT_INVALID_INPUT,
					"Invalid Java version '" + version
							+ "', should be a version like 17, 17+, 25.0.3 or 25.0.3+");
		}
		String v = version.trim();
		boolean open = v.endsWith("+");
		String num = open ? v.substring(0, v.length() - 1) : v;
		String[] split = num.split("\\.");
		int[] parts = new int[split.length];
		for (int i = 0; i < split.length; i++) {
			parts[i] = Integer.parseInt(split[i]);
		}
		return new RequestedVersion(v, normalize(parts), open);
	}

	public static RequestedVersion ofMajor(int major, boolean open) {
		return parse(major + (open ? "+" : ""));
	}

	/** The major version, e.g. 25 for both "25" and "25.0.3". */
	public int major() {
		return parts[0];
	}

	/** True if later versions are acceptable too (the request ended in "+"). */
	public boolean isOpen() {
		return open;
	}

	/** True if only one major version can satisfy this request. */
	public boolean isMajorOnly() {
		return parts.length == 1;
	}

	/**
	 * Checks a concrete version string, as found in a JDK's release file or in
	 * the JDK index (e.g. "25.0.3+9", "1.8.0_452" or "8.0-492").
	 */
	public boolean matches(String actualVersion) {
		int[] actual = componentsOf(actualVersion);
		if (actual.length == 0) {
			return false;
		}
		if (open) {
			return compare(actual, parts) >= 0;
		}
		// without a "+" the requested components must be a prefix of the actual ones
		if (actual.length < parts.length) {
			return false;
		}
		for (int i = 0; i < parts.length; i++) {
			if (actual[i] != parts[i]) {
				return false;
			}
		}
		return true;
	}

	/** Splits a version string into its numeric components. */
	public static int[] componentsOf(String version) {
		if (version == null) {
			return new int[0];
		}
		List<Integer> nums = new ArrayList<>();
		int i = 0;
		while (i < version.length() && nums.size() < 8) {
			if (Character.isDigit(version.charAt(i))) {
				int start = i;
				while (i < version.length() && Character.isDigit(version.charAt(i))) {
					i++;
				}
				try {
					nums.add(Integer.parseInt(version.substring(start, i)));
				} catch (NumberFormatException e) {
					break;
				}
			} else {
				// stop at the build separator, "25.0.3+9" and "8u452-b09" only
				// carry version information before it
				if (version.charAt(i) == '+' || version.charAt(i) == '_') {
					break;
				}
				i++;
			}
		}
		int[] parts = new int[nums.size()];
		for (int j = 0; j < parts.length; j++) {
			parts[j] = nums.get(j);
		}
		return normalize(parts);
	}

	/** Turns the legacy "1.8.0" numbering into "8.0". */
	private static int[] normalize(int[] parts) {
		if (parts.length > 1 && parts[0] == 1 && parts[1] >= 2 && parts[1] <= 8) {
			int[] shifted = new int[parts.length - 1];
			System.arraycopy(parts, 1, shifted, 0, shifted.length);
			return shifted;
		}
		return parts;
	}

	/** Compares version components, treating missing trailing components as 0. */
	public static int compare(int[] a, int[] b) {
		for (int i = 0; i < Math.max(a.length, b.length); i++) {
			int x = i < a.length ? a[i] : 0;
			int y = i < b.length ? b[i] : 0;
			if (x != y) {
				return Integer.compare(x, y);
			}
		}
		return 0;
	}

	@Override
	public int compareTo(RequestedVersion other) {
		int c = compare(parts, other.parts);
		if (c != 0) {
			return c;
		}
		if (open == other.open) {
			return 0;
		}
		// an exact request is considered higher than an open one
		return open ? -1 : 1;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof RequestedVersion && text.equals(((RequestedVersion) o).text);
	}

	@Override
	public int hashCode() {
		return text.hashCode();
	}

	@Override
	public String toString() {
		return text;
	}
}
