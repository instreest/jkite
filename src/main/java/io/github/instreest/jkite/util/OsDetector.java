/*
 * Copyright 2014 Trustin Heuiseung Lee.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Modified for jkite: the normalisation of os.name and os.arch is taken from
 * kr.motd.maven.os.Detector, with the detection of the Linux release, the
 * Maven plumbing and everything else that file does left out, and with the
 * os.detected.* properties written into a Properties of jkite's own. The
 * tables themselves are unchanged, because a classifier that differs from
 * os-maven-plugin's would not match what is published on Maven Central.
 */
package io.github.instreest.jkite.util;

import java.util.Locale;
import java.util.Properties;

/**
 * Provides the <code>os.detected.*</code> properties (name, arch, classifier,
 * jfxname) that can be referenced from directives, e.g.
 * <code>//DEPS org.openjfx:javafx-base:21:${os.detected.jfxname}</code>. The
 * normalisation rules follow the well known os-maven-plugin (Trustin Lee,
 * Apache License 2.0) so that classifiers match what is published on Maven
 * Central. See THIRD-PARTY.md.
 */
public final class OsDetector {
	public static final String PREFIX = "os.detected.";

	private OsDetector() {
	}

	public static void detect(Properties props) {
		String name = normalizeOs(System.getProperty("os.name"));
		String arch = normalizeArch(System.getProperty("os.arch"));
		props.setProperty(PREFIX + "name", name);
		props.setProperty(PREFIX + "arch", arch);
		props.setProperty(PREFIX + "classifier", name + "-" + arch);
		props.setProperty(PREFIX + "jfxname", jfxName(name));
	}

	private static String jfxName(String name) {
		if ("osx".equals(name)) {
			return "aarch64".equals(System.getProperty("os.arch")) ? "mac-aarch64" : "mac";
		} else if ("windows".equals(name)) {
			return "win";
		}
		return name;
	}

	static String normalizeOs(String value) {
		value = normalize(value);
		if (value.startsWith("aix")) {
			return "aix";
		} else if (value.startsWith("hpux")) {
			return "hpux";
		} else if (value.startsWith("os400")) {
			if (value.length() <= 5 || !Character.isDigit(value.charAt(5))) {
				return "os400";
			}
		} else if (value.startsWith("linux")) {
			return "linux";
		} else if (value.startsWith("mac") || value.startsWith("osx") || value.startsWith("darwin")) {
			return "osx";
		} else if (value.startsWith("freebsd")) {
			return "freebsd";
		} else if (value.startsWith("openbsd")) {
			return "openbsd";
		} else if (value.startsWith("netbsd")) {
			return "netbsd";
		} else if (value.startsWith("solaris") || value.startsWith("sunos")) {
			return "sunos";
		} else if (value.startsWith("windows")) {
			return "windows";
		} else if (value.startsWith("zos")) {
			return "zos";
		}
		return "unknown";
	}

	static String normalizeArch(String value) {
		value = normalize(value);
		if (value.matches("^(x8664|amd64|ia32e|em64t|x64)$")) {
			return "x86_64";
		} else if (value.matches("^(x8632|x86|i[3-6]86|ia32|x32)$")) {
			return "x86_32";
		} else if (value.matches("^(ia64w?|itanium64)$")) {
			return "itanium_64";
		} else if ("ia64n".equals(value)) {
			return "itanium_32";
		} else if (value.matches("^(sparc|sparc32)$")) {
			return "sparc_32";
		} else if (value.matches("^(sparcv9|sparc64)$")) {
			return "sparc_64";
		} else if (value.matches("^(arm|arm32)$")) {
			return "arm_32";
		} else if ("aarch64".equals(value)) {
			return "aarch_64";
		} else if (value.matches("^(mips|mips32)$")) {
			return "mips_32";
		} else if (value.matches("^(mipsel|mips32el)$")) {
			return "mipsel_32";
		} else if ("mips64".equals(value)) {
			return "mips_64";
		} else if ("mips64el".equals(value)) {
			return "mipsel_64";
		} else if (value.matches("^(ppc|ppc32)$")) {
			return "ppc_32";
		} else if (value.matches("^(ppcle|ppc32le)$")) {
			return "ppcle_32";
		} else if ("ppc64".equals(value)) {
			return "ppc_64";
		} else if ("ppc64le".equals(value)) {
			return "ppcle_64";
		} else if ("s390".equals(value)) {
			return "s390_32";
		} else if ("s390x".equals(value)) {
			return "s390_64";
		} else if (value.matches("^(riscv|riscv32)$")) {
			return "riscv";
		} else if ("riscv64".equals(value)) {
			return "riscv64";
		} else if ("e2k".equals(value)) {
			return "e2k";
		} else if ("loongarch64".equals(value)) {
			return "loongarch_64";
		}
		return "unknown";
	}

	private static String normalize(String value) {
		return value == null ? "" : value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "");
	}
}
