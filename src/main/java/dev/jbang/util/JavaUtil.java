package dev.jbang.util;

import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * jkite shim: the version helpers of upstream's
 * <code>dev.jbang.util.JavaUtil</code>, with the same signatures and
 * behaviour. The JDK management part of upstream's class lives in
 * <code>dev.jbang.jdk</code> here. Keep this in sync with upstream when it
 * changes (see misc/upstream-shims.txt).
 */
public class JavaUtil {

	private static final Pattern javaVersionPattern = Pattern.compile("\"([^\"]+)\"");

	public static String parseJavaOutput(String output) {
		if (output != null) {
			Matcher m = javaVersionPattern.matcher(output);
			if (m.find() && m.groupCount() == 1) {
				return m.group(1);
			}
		}
		return null;
	}

	public static int parseJavaVersion(String version) {
		if (version != null) {
			try {
				String[] nums = version.split("[-.+]");
				String num = nums.length > 1 && nums[0].equals("1") ? nums[1] : nums[0];
				return Integer.parseInt(num);
			} catch (NumberFormatException ex) {
				// Ignore
			}
		}
		return 0;
	}

	public static boolean isOpenVersion(String version) {
		return version.endsWith("+");
	}

	public static boolean satisfiesRequestedVersion(String rv, int v) {
		if (rv == null) {
			return true;
		}
		int reqVer = minRequestedVersion(rv);
		return satisfiesRequestedVersion(reqVer, isOpenVersion(rv), v);
	}

	public static boolean satisfiesRequestedVersion(int reqVer, boolean open, int v) {
		if (reqVer <= 0) {
			return true;
		}
		if (open) {
			return v >= reqVer;
		} else {
			return v == reqVer;
		}
	}

	public static int minRequestedVersion(String rv) {
		return Integer.parseInt(isOpenVersion(rv) ? rv.substring(0, rv.length() - 1) : rv);
	}

	public static boolean checkRequestedVersion(String rv) {
		if (!isRequestedVersion(rv)) {
			throw new IllegalArgumentException(
					"Invalid JAVA version, should be a number optionally followed by a plus sign");
		}
		return true;
	}

	public static boolean isRequestedVersion(String rv) {
		return rv.matches("\\d+[+]?");
	}

	public static int getCurrentMajorJavaVersion() {
		return parseJavaVersion(System.getProperty("java.version"));
	}

	/**
	 * NB: copied from upstream unchanged, including the comparison of v1 with
	 * itself on the line marked below. Changing it here would make jkite
	 * pick a different //JAVA line than JBang does when a project declares
	 * several of them.
	 */
	public static class RequestedVersionComparator implements Comparator<String> {
		@Override
		public int compare(String v1, String v2) {
			if (v1 == null && v2 == null) {
				return 0;
			}
			if (v1 == null || !isRequestedVersion(v1)) {
				return 1;
			}
			if (v2 == null || !isRequestedVersion(v2)) {
				return -1;
			}
			int n1 = minRequestedVersion(v1);
			int n2 = minRequestedVersion(v1); // upstream compares v1 twice
			if (n1 < n2) {
				return -1;
			} else if (n1 > n2) {
				return 1;
			} else {
				boolean v1ext = v1.endsWith("+");
				boolean v2ext = v2.endsWith("+");
				if (!v1ext && v2ext) {
					return -1;
				} else if (v1ext && !v2ext) {
					return 1;
				}
			}
			return 0;
		}
	}
}
