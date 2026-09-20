package io.github.instreest.jkite;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import dev.jbang.ExitException;
import dev.jbang.util.Util;

/**
 * Locations and environment driven settings. The directory layout is kept
 * identical to the one used by the full JBang so that the launcher scripts
 * (and tools such as java-call-hierarchy-exporter) keep working:
 *
 * <pre>
 * $JKITE_DIR (~/.jkite)
 *   cache/            ($JKITE_CACHE_DIR)
 *     jars/           compiled scripts
 *     jdks/           JDKs installed by jkite
 *     deps/           the local Maven repository: the dependency jars
 *     urls/           files being downloaded
 *     .locks/         held while a cache entry is written
 *     dependency_cache.txt
 * </pre>
 *
 * Every one of them can be thrown away: the next run builds or fetches what it
 * needs again. <code>--clear-cache</code> does that for all of them but
 * <code>jdks/</code>, which it keeps and names, because a JDK is pinned and
 * costs minutes to fetch again.
 */
public final class Settings {
	public static final String ENV_DIR = "JKITE_DIR";
	public static final String ENV_CACHE_DIR = "JKITE_CACHE_DIR";
	public static final String ENV_MAVEN_REPO = "JKITE_MAVEN_REPO";
	public static final String ENV_DEFAULT_JAVA_VERSION = "JKITE_DEFAULT_JAVA_VERSION";
	public static final String ENV_JDK_INDEX = "JKITE_JDK_INDEX";
	public static final String ENV_DOWNLOAD_RETRY = "JKITE_DOWNLOAD_RETRY";
	public static final String ENV_DOWNLOAD_RETRY_DELAY = "JKITE_DOWNLOAD_RETRY_DELAY";
	/** auto / always / never: whether a download is confirmed before it starts. */
	public static final String ENV_CONFIRM_DOWNLOADS = "JKITE_CONFIRM_DOWNLOADS";
	/** Seconds to wait for a cache lock before giving up, 0 meaning forever. */
	public static final String ENV_LOCK_TIMEOUT = "JKITE_LOCK_TIMEOUT";
	public static final String ENV_ASSUME_YES = "JKITE_ASSUME_YES";

	public static final String CP_SEPARATOR = File.pathSeparator;
	public static final String DEPENDENCY_CACHE_FILE = "dependency_cache.txt";

	public static final int DEFAULT_JAVA_VERSION = 17;
	/** The one JDK distribution jkite installs: Eclipse Temurin. */
	public static final String JDK_DISTRO = "temurin";
	/**
	 * The host {@link #JDK_DISTRO} publishes its archives on. The JVM index says
	 * where to download a JDK from, and nothing else vouches for what it says, so
	 * an archive is only fetched from here. Every Temurin entry in the index
	 * points at this host; one that does not is a reason to stop, not to follow.
	 */
	public static final String JDK_DOWNLOAD_HOST = "github.com";
	/**
	 * The account on {@link #JDK_DOWNLOAD_HOST} that publishes them. The host
	 * alone is not enough: anyone can put a release on github.com, so a URL is
	 * only followed when it also comes from this account. Every Temurin entry in
	 * the index is under it.
	 */
	public static final String JDK_DOWNLOAD_PATH_PREFIX = "/adoptium/";
	/**
	 * Where a download from {@link #JDK_DOWNLOAD_HOST} is allowed to redirect.
	 * GitHub answers a release asset with a redirect to a content host whose
	 * name it has changed before and will change again, so the suffix is
	 * allowed rather than one name that would turn a GitHub change into a jkite
	 * outage.
	 */
	public static final String JDK_REDIRECT_HOST_SUFFIX = ".githubusercontent.com";
	public static final int DEFAULT_DOWNLOAD_RETRY = 5;
	/**
	 * Ten minutes, which is what the bootstrap scripts already wait: the same
	 * variable governs the lock they take before Java exists and the lock
	 * taken after it, and a variable that means two things is worse than
	 * either meaning.
	 */
	public static final int DEFAULT_LOCK_TIMEOUT = 600;

	public enum CacheClass {
		urls, jars, jdks, deps
	}

	private Settings() {
	}

	public static Path getConfigDir() {
		String jd = System.getenv(ENV_DIR);
		Path dir = jd != null ? Paths.get(jd) : homeDir().resolve(".jkite");
		return mkdirs(dir);
	}

	/**
	 * Where the launcher would have put things, which is what this has to
	 * agree with.
	 *
	 * The launchers decide this before there is a JVM to ask: the POSIX one
	 * uses $HOME, jkite.cmd uses %USERPROFILE%. Java's user.home is neither of
	 * those on Linux - it comes from the passwd entry - so anything that sets
	 * HOME without changing the passwd entry makes the two disagree, and a
	 * container image, a systemd unit and "sudo -u" all do exactly that. The
	 * run then keeps its jar in one tree and its JDKs and dependency cache in
	 * another, and in the common container case where the passwd entry says
	 * /nonexistent the second tree cannot be written at all - after the
	 * launcher has already worked.
	 *
	 * user.home stays as the fallback, for a login with no HOME in its
	 * environment at all.
	 */
	private static Path homeDir() {
		return homeDir(System.getenv(Util.isWindows() ? "USERPROFILE" : "HOME"),
				System.getProperty("user.home"));
	}

	static Path homeDir(String fromEnvironment, String userHome) {
		if (fromEnvironment != null && !fromEnvironment.trim().isEmpty()) {
			Path dir = Paths.get(fromEnvironment.trim());
			// a relative HOME would put the cache wherever the run started,
			// which is neither what the launcher did nor anything anyone wants
			if (dir.isAbsolute()) {
				return dir;
			}
		}
		return Paths.get(userHome);
	}

	public static Path getCacheDir() {
		String v = System.getenv(ENV_CACHE_DIR);
		Path dir = v != null ? Paths.get(v) : getConfigDir().resolve("cache");
		return mkdirs(dir);
	}

	/**
	 * One kind of cached thing, always under the cache directory.
	 *
	 * There used to be a per-kind override here, JKITE_CACHE_DIR_JARS and its
	 * two siblings, inherited and never documented. It did two unhelpful
	 * things. The launcher does not read them - it knows only JKITE_CACHE_DIR
	 * - so setting the JDKs one gave a machine two JDK caches and two
	 * downloads of the same JDK, one for each half of a run. And
	 * --clear-cache removes the contents of the jars and urls directories, so
	 * an undocumented variable decided which directory that was. Neither was
	 * worth a setting nobody had written down.
	 */
	public static Path getCacheDir(CacheClass cclass) {
		return mkdirs(getCacheDir().resolve(cclass.name()));
	}

	public static Path getDependencyCacheFile() {
		return getCacheDir().resolve(DEPENDENCY_CACHE_FILE);
	}

	/**
	 * The local Maven repository: jkite's own, under the cache, unless
	 * JKITE_MAVEN_REPO names another.
	 *
	 * Not ~/.m2/repository, which is what Maven Resolver would pick by
	 * itself. Everything else a run needs is pinned by a SHA-256 the project
	 * commits and lands under JKITE_DIR; the dependencies were the one thing
	 * that came out of a directory jkite neither pins nor owns. That made two
	 * claims untrue at once. "Everything jkite writes goes under JKITE_DIR"
	 * was not, and emptying JKITE_DIR did not return the machine to a cold
	 * one, because the jars that actually reach the class path were still
	 * sitting in ~/.m2 - which, since the JVM takes user.home from the passwd
	 * entry rather than from HOME, could not even be moved out of the way.
	 * Whatever another build did to that directory decided what a jkite run
	 * compiled against, without saying so.
	 *
	 * The cost is real and is the reason this is not obviously right: a
	 * machine that already holds an artifact in ~/.m2 fetches it again. That
	 * is the price of a run being the project's rather than the machine's,
	 * and JKITE_MAVEN_REPO=~/.m2/repository buys the sharing back for anyone
	 * who would rather have it.
	 *
	 * Only the directory of files moves. Mirrors, proxies and credentials are
	 * still read from ~/.m2/settings.xml, because those describe the machine's
	 * route to a repository, which is exactly what jkite should not be
	 * reinventing. A &lt;localRepository&gt; in that file is overridden, like
	 * Maven's own default.
	 */
	public static Path getLocalMavenRepo() {
		return localMavenRepo(System.getenv(ENV_MAVEN_REPO));
	}

	/**
	 * The same, with the variable passed in, so that the default can be
	 * tested. The test run sets JKITE_MAVEN_REPO - it wants a repository of
	 * its own - and a test that read the environment would therefore never
	 * see the default it exists to pin.
	 */
	static Path localMavenRepo(String override) {
		return override != null ? Paths.get(override) : getCacheDir(CacheClass.deps);
	}

	public static int getDefaultJavaVersion() {
		String v = System.getenv(ENV_DEFAULT_JAVA_VERSION);
		if (v != null) {
			try {
				return Integer.parseInt(v.trim());
			} catch (NumberFormatException e) {
				Util.warnMsg("Ignoring invalid " + ENV_DEFAULT_JAVA_VERSION + ": " + v);
			}
		}
		return DEFAULT_JAVA_VERSION;
	}

	/** Number of extra download attempts, see also the launcher scripts. */
	public static int getDownloadRetry() {
		return intFromEnv(ENV_DOWNLOAD_RETRY, DEFAULT_DOWNLOAD_RETRY);
	}

	/** Seconds between download attempts, 0 meaning exponential backoff. */
	public static int getDownloadRetryDelay() {
		return intFromEnv(ENV_DOWNLOAD_RETRY_DELAY, 0);
	}

	/**
	 * How long to wait for another jkite process to release a cache lock,
	 * in seconds; 0 waits for as long as it takes.
	 */
	public static int getLockTimeout() {
		return intFromEnv(ENV_LOCK_TIMEOUT, DEFAULT_LOCK_TIMEOUT);
	}

	/**
	 * How the download gate asks before fetching a JDK or dependencies:
	 * "auto" (the default: only when there is a terminal to ask on), "always"
	 * or "never".
	 */
	public static String getConfirmDownloads() {
		String v = System.getenv(ENV_CONFIRM_DOWNLOADS);
		return v != null && !v.trim().isEmpty() ? v.trim().toLowerCase() : "auto";
	}

	/** JKITE_ASSUME_YES, the environment's form of <code>--yes</code>. */
	public static boolean isAssumeYes() {
		String v = System.getenv(ENV_ASSUME_YES);
		if (v == null) {
			return false;
		}
		String s = v.trim().toLowerCase();
		return s.equals("1") || s.equals("true") || s.equals("yes");
	}

	private static int intFromEnv(String name, int defaultValue) {
		String v = System.getenv(name);
		if (v != null && !v.trim().isEmpty()) {
			try {
				return Integer.parseInt(v.trim());
			} catch (NumberFormatException e) {
				Util.warnMsg("Ignoring invalid " + name + ": " + v);
			}
		}
		return defaultValue;
	}

	/**
	 * Throws away what the cache can produce again: the built jars, whatever a
	 * download left behind, the resolved class paths, and the dependency jars
	 * they point at. The installed JDKs are left where they are - they are
	 * pinned, there are few of them, and fetching one again costs minutes -
	 * and so is jkite's own jar, which is running.
	 *
	 * The dependency jars are in that list because of what this option is
	 * for. Emptying the cache is how somebody asks for the next run to start
	 * from nothing, and while the jars lived in ~/.m2 that was not what they
	 * got: the resolved class paths went and the files they named stayed, so
	 * the next run used the same bytes as the last one and the option had not
	 * done the one thing its name promises. Only jkite's own repository is
	 * emptied - a JKITE_MAVEN_REPO pointing at ~/.m2/repository, or anywhere
	 * else somebody chose, is somebody else's directory and is left alone.
	 *
	 * @param say where each line goes, as it happens
	 */
	public static void clearCache(Consumer<String> say) {
		clearCache(say, System.getenv(ENV_MAVEN_REPO));
	}

	/** The same, with the variable passed in, as for localMavenRepo above. */
	static void clearCache(Consumer<String> say, String mavenRepoOverride) {
		Path jars = getCacheDir(CacheClass.jars);
		Path urls = getCacheDir(CacheClass.urls);
		Path deps = getDependencyCacheFile();
		// null when JKITE_MAVEN_REPO points somewhere of the user's own
		Path repo = mavenRepoOverride == null ? getCacheDir(CacheClass.deps) : null;
		// Printed as it goes rather than collected and returned, so that the
		// list of what is about to be removed is on the screen before it is:
		// jkite asks before it downloads, and the one thing it does that
		// cannot be undone should at least say what it is about to do it to
		// while there is still time to read it. A JKITE_DIR or
		// JKITE_CACHE_DIR pointing somewhere unintended is visible here and
		// nowhere else.
		say.accept("Removing the contents of:");
		say.accept("  " + jars + "   (built jars)");
		say.accept("  " + urls + "   (unfinished downloads)");
		if (repo != null) {
			say.accept("  " + repo + "   (dependency jars)");
		}
		if (Files.exists(deps)) {
			say.accept("  " + deps + "   (resolved dependencies)");
		}
		say.accept("");
		say.accept(removeContents(jars, "built jars"));
		say.accept(removeContents(urls, "unfinished downloads"));
		if (repo != null) {
			// counted before it goes, because removeContents counts what it
			// deletes and what it deletes here is a handful of top-level group
			// directories - "removed 3 dependency jars" for three hundred of
			// them is worse than saying nothing
			long depJars = countJars(repo);
			say.accept(removeContents(repo, "dependency trees holding " + depJars + (depJars == 1 ? " jar" : " jars")));
		} else {
			say.accept("kept " + mavenRepoOverride + " (" + ENV_MAVEN_REPO
					+ " names it, so it is not jkite's to empty)");
		}
		if (Files.exists(deps)) {
			say.accept(Util.deletePath(deps, true)
					? "removed the resolved dependencies of " + deps
					: "could not remove " + deps);
		}
		say.accept("kept the JDKs in " + getCacheDir(CacheClass.jdks)
				+ " (remove that directory by hand to fetch them again)");
	}

	/** How many jars are under a local repository, for the message above. */
	private static long countJars(Path repo) {
		try (Stream<Path> found = Files.walk(repo)) {
			return found.filter(p -> p.getFileName().toString().endsWith(".jar")).count();
		} catch (IOException e) {
			return 0;
		}
	}

	private static String removeContents(Path dir, String what) {
		int removed = 0;
		int kept = 0;
		try (Stream<Path> entries = Files.list(dir)) {
			for (Path entry : entries.collect(Collectors.toList())) {
				if (Util.deletePath(entry, true)) {
					removed++;
				} else {
					kept++;
				}
			}
		} catch (IOException e) {
			return "could not read " + dir + ": " + e;
		}
		return "removed " + removed + " " + what + " from " + dir
				+ (kept > 0 ? " (" + kept + " could not be removed)" : "");
	}

	/** The lock files that keep concurrent runs out of each other's writes. */
	public static Path getLockDir() {
		return mkdirs(getCacheDir().resolve(".locks"));
	}

	/**
	 * Creates the directory if it is not there yet. A failure is reported here,
	 * where the directory and the variable that named it are still known;
	 * leaving it to whatever writes there next turns "the cache directory
	 * cannot be created" into an unrelated-looking error further on.
	 */
	private static Path mkdirs(Path dir) {
		if (!Files.isDirectory(dir) && !dir.toFile().mkdirs() && !Files.isDirectory(dir)) {
			throw new ExitException(ExitException.EXIT_UNEXPECTED_STATE,
					"Could not create the directory " + dir + ". Set " + ENV_DIR + " or " + ENV_CACHE_DIR
							+ " to a directory that can be written to.");
		}
		return dir;
	}
}
