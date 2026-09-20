package dev.jbang.dependencies;

import static dev.jbang.util.Util.isWindows;
import static dev.jbang.util.Util.verboseMsg;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class DependencyUtil {
	// jkite shim: same API as upstream except resolveDependencies(), which
	// lives in io.github.instreest.jkite.dependencies.DependencyResolver here. Keep the rest in
	// sync with upstream when it changes (see misc/upstream-shims.txt).


	public static final String ALIAS_JITPACK = "jitpack";
	public static final String REPO_JITPACK = "https://jitpack.io/";

	private static final Map<String, String> aliasToRepos;

	static {
		aliasToRepos = new HashMap<>();
		aliasToRepos.put("mavencentral", "https://repo1.maven.org/maven2/"); // deprecated; kept for backwards
																				// compatability
		aliasToRepos.put("central", "https://repo1.maven.org/maven2/"); // deprecated; kept for backwards compatability
		aliasToRepos.put("jbossorg", "https://repository.jboss.org/nexus/content/groups/public/");
		aliasToRepos.put("redhat", "https://maven.repository.redhat.com/ga/");
		aliasToRepos.put("jcenter", "https://jcenter.bintray.com/");
		aliasToRepos.put("google", "https://maven.google.com/");
		aliasToRepos.put(ALIAS_JITPACK, REPO_JITPACK);
		aliasToRepos.put("sponge", "https://repo.spongepowered.org/maven");
		aliasToRepos.put("central-portal-snapshots", "https://central.sonatype.com/repository/maven-snapshots/");

		aliasToRepos.put("sonatype-snapshots", "https://oss.sonatype.org/content/repositories/snapshots");
		aliasToRepos.put("s01sonatype-snapshots", "https://s01.oss.sonatype.org/content/repositories/snapshots");
		aliasToRepos.put("spring-snapshot", "https://repo.spring.io/snapshot");
		aliasToRepos.put("spring-milestone", "https://repo.spring.io/milestone");
		aliasToRepos.put("jogamp", "https://jogamp.org/deployment/maven");
		aliasToRepos.put("mvnpm", "https://repo.mvnpm.org/maven2");
	}

	public static final Pattern fullGavPattern = Pattern.compile(
			"^(?<groupid>[a-zA-Z0-9_.-]*):(?<artifactid>[a-zA-Z0-9_.-]*):(?<version>[^:@]*)(:(?<classifier>[^@]*))?(@(?<type>.*))?$");

	public static final Pattern lenientGavPattern = Pattern.compile(
			"^(?<groupid>[a-zA-Z0-9_.-]*):(?<artifactid>[a-zA-Z0-9_.-]*)(:(?<version>[^:@]*)(:(?<classifier>[^@]*))?)?(@(?<type>.*))?$");

	private DependencyUtil() {
	}

	public static String decodeEnv(String value) {
		if (value.startsWith("{{") && value.endsWith("}}")) {
			String envKey = value.substring(2, value.length() - 2);
			String envValue = System.getenv(envKey);
			if (null == envValue) {
				throw new IllegalStateException(String.format(
						"Could not resolve environment variable {{%s}} in maven repository credentials", envKey));
			}
			return envValue;
		} else {
			return value;
		}
	}

	public static boolean looksLikeAGav(String candidate) {
		Matcher gav = fullGavPattern.matcher(candidate);
		gav.find();
		return (gav.matches());
	}

	public static boolean looksLikeAPossibleGav(String candidate) {
		Matcher gav = lenientGavPattern.matcher(candidate);
		gav.find();
		return (gav.matches());
	}

	public static String formatVersion(String version) {
		// replace + with open version range for maven
		if (version != null && version.endsWith("+")) {
			return "[" + removeLastCharOptional(version) + ",)";
		} else {
			return version;
		}
	}

	public static String removeLastCharOptional(String s) {
		return Optional.ofNullable(s)
			.filter(str -> str.length() != 0)
			.map(str -> str.substring(0, str.length() - 1))
			.orElse(s);
	}

	public static MavenRepo toMavenRepo(String repoReference) {
		String[] split = repoReference.split("=");
		String reporef = null;
		String repoid = null;

		if (split.length == 1) {
			reporef = split[0];
			repoid = reporef.toLowerCase();
		} else if (split.length == 2) {
			repoid = split[0];
			reporef = split[1];
		} else {
			throw new IllegalStateException("Invalid Maven repository reference: " + repoReference);
		}

		String repo = aliasToRepos.get(reporef.toLowerCase());
		if (repo != null) {
			return new MavenRepo(repoid, repo);
		} else if (isRelativePathRepoReference(reporef)) {
			Path base = Paths.get(".").toAbsolutePath().normalize();
			Path resolved = base.resolve(reporef).normalize();
			String resolvedUrl = resolved.toUri().toString();
			verboseMsg("Maven local path resolved: " + repoReference + " -> " + resolvedUrl);
			// TODO: should we throw an error if the resolved path is not a child of the
			// base path?
			return new MavenRepo(repoid, resolvedUrl);
		} else {
			return new MavenRepo(repoid, reporef);
		}
	}

	private static boolean isRelativePathRepoReference(String reporef) {
		if (reporef.startsWith("./") || reporef.startsWith("../")) {
			return true;
		}
		// On Windows, relative paths can use \ but on linux that will fail.
		// to be nice to windows we accept it but if users want to be portable best to
		// use /
		return isWindows() && (reporef.startsWith(".\\") || reporef.startsWith("..\\"));
	}
}
