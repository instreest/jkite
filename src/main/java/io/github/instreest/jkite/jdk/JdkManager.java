package io.github.instreest.jkite.jdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import dev.jbang.ExitException;
import io.github.instreest.jkite.Settings;
import io.github.instreest.jkite.spi.DownloadGate;
import io.github.instreest.jkite.spi.Providers;
import io.github.instreest.jkite.util.CacheLock;
import io.github.instreest.jkite.util.RequestedVersion;
import dev.jbang.util.Util;

/**
 * Finds JDKs already present on the machine and installs missing ones from the
 * download URLs listed in the {@link JdkIndex}. Search order for a requested
 * version:
 * <ol>
 * <li>the JVM running jkite</li>
 * <li>JAVA_HOME</li>
 * <li>javac found on the PATH</li>
 * <li>JDKs installed by jkite in the cache ($JKITE_CACHE_DIR/jdks)</li>
 * <li>download and install into the cache</li>
 * </ol>
 */
public final class JdkManager {
	private final Path jdksDir;
	private final int defaultJavaVersion;
	private List<Jdk> installed;

	public JdkManager() {
		this(Settings.getCacheDir(Settings.CacheClass.jdks), Settings.getDefaultJavaVersion());
	}

	JdkManager(Path jdksDir, int defaultJavaVersion) {
		this.jdksDir = jdksDir;
		this.defaultJavaVersion = defaultJavaVersion;
	}

	/**
	 * Returns a JDK matching the requested version ("17", "17+", "25.0.3" or
	 * null for any), installing one if necessary.
	 */
	public Jdk getOrInstallJdk(String requestedVersion) {
		RequestedVersion version = requestedVersion != null
				? RequestedVersion.parse(requestedVersion)
				: RequestedVersion.ofMajor(defaultJavaVersion, true);
		Jdk jdk = getInstalledJdk(version);
		if (jdk == null) {
			jdk = install(version);
		}
		Util.verboseMsg("Using JDK: " + jdk + " [" + jdk.origin().label() + "]");
		InstallRecord.read(jdk.home()).ifPresent(record -> Util.verboseMsg("   installed: " + record.describe()));
		return jdk;
	}

	/** Returns an already installed JDK matching the version, or null. */
	public Jdk getInstalledJdk(RequestedVersion version) {
		return listInstalled().stream()
			.filter(j -> version.matches(j.version()))
			.findFirst()
			.orElse(null);
	}

	/** All JDKs found, in search order (deduplicated by real path). */
	public List<Jdk> listInstalled() {
		if (installed == null) {
			List<Jdk> jdks = new ArrayList<>();
			add(jdks, Jdk.of(jre2jdk(Paths.get(System.getProperty("java.home"))), Jdk.Origin.CURRENT));
			String javaHome = System.getenv("JAVA_HOME");
			if (javaHome != null && !javaHome.isEmpty()) {
				add(jdks, Jdk.of(jre2jdk(Paths.get(javaHome)), Jdk.Origin.JAVA_HOME));
			}
			Path javac = Util.searchPath("javac");
			if (javac != null) {
				try {
					Path home = javac.toRealPath().getParent().getParent();
					add(jdks, Jdk.of(home, Jdk.Origin.PATH));
				} catch (IOException e) {
					Util.verboseMsg("Could not resolve javac on PATH: " + e);
				}
			}
			listCachedJdks().forEach(j -> add(jdks, j));
			installed = jdks;
		}
		return installed;
	}

	private static void add(List<Jdk> jdks, Jdk jdk) {
		if (jdk != null && jdks.stream().noneMatch(j -> sameHome(j.home(), jdk.home()))) {
			jdks.add(jdk);
		}
	}

	private static boolean sameHome(Path a, Path b) {
		try {
			return Files.isSameFile(a, b);
		} catch (IOException e) {
			return a.toAbsolutePath().equals(b.toAbsolutePath());
		}
	}

	/** JDKs jkite downloaded into its own cache, newest first. */
	public List<Jdk> listCachedJdks() {
		if (!Files.isDirectory(jdksDir)) {
			return new ArrayList<>();
		}
		try (Stream<Path> dirs = Files.list(jdksDir)) {
			return dirs
				.filter(Files::isDirectory)
				.filter(d -> !d.getFileName().toString().endsWith(".tmp"))
				.map(d -> Jdk.of(d, Jdk.Origin.CACHE))
				.filter(Objects::nonNull)
				.sorted(Comparator
					.comparing((Jdk j) -> RequestedVersion.componentsOf(j.version()),
							(a, b) -> RequestedVersion.compare(a, b))
					.reversed())
				.collect(Collectors.toList());
		} catch (IOException e) {
			Util.verboseMsg("Could not list " + jdksDir + ": " + e);
			return new ArrayList<>();
		}
	}

	/**
	 * Downloads and installs a JDK satisfying the request into
	 * $JKITE_CACHE_DIR/jdks/&lt;version&gt;. The archive's SHA-256 is verified
	 * against the checksum published next to it. A lock file makes concurrent
	 * jkite processes wait for each other instead of installing on top of one
	 * another. Nothing outside that directory is touched: running a script
	 * never changes which JDK the next run picks.
	 */
	public Jdk install(RequestedVersion version) {
		requireAnInstallableJdk(Util.getOS(), System.getenv(Settings.ENV_JDK_INDEX));
		if (Util.isOffline()) {
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
					"No suitable JDK was found for requested version " + version + " and we are offline");
		}
		// asked before the index is fetched, because reading it is already a
		// download; the entry that is then chosen is named by the message
		// download() prints
		Providers.downloadGate()
			.check(new DownloadGate.Request(DownloadGate.Kind.JDK,
					"No JDK matching Java " + version + " was found on this machine.",
					Collections.singletonList("a JDK for Java " + version + ", and the JDK index listing it, "
							+ "into " + jdksDir)));
		JdkIndex.Entry entry = selectEntry(version);
		Path jdkDir = jdksDir.resolve(entry.version);
		try (CacheLock lock = CacheLock.acquireAt(jdksDir.resolve(".locks").resolve(entry.version + ".lock"),
				"Waiting for another jkite process to finish installing a JDK...")) {
			if (!lock.isHeld()) {
				Util.warnMsg("Could not lock the JDK directory. If another jkite is installing a JDK"
						+ " at the same time, one of the two installations may fail.");
			}
			// another process may have installed this exact entry while we were
			// waiting for the lock; the directory is named after the entry, so
			// finding a JDK there means there is nothing left to download
			installed = null;
			Jdk existing = Jdk.of(jdkDir, Jdk.Origin.CACHE);
			if (existing != null) {
				Util.verboseMsg("JDK " + entry.version + " is already installed: " + existing);
				return finish(existing);
			}
			Jdk jdk = download(entry, jdkDir);
			if (!version.matches(jdk.version())) {
				Util.warnMsg("The JDK index listed version " + entry.version + " for the request '"
						+ version + "' but the installed JDK reports " + jdk.version());
			}
			return finish(jdk);
		}
	}

	/**
	 * Stops before a JDK that could not run here is fetched.
	 *
	 * The JDKs in the index are built against glibc and do not run on musl,
	 * so on Alpine there is nothing here to install. The launcher has always
	 * said so and stopped - jkite-bootstrap-jdk matches /etc/alpine-release
	 * and refuses by name - and README says a JDK has to be installed by
	 * hand there. This half only warned, and then went on to spend several
	 * minutes fetching two hundred megabytes of JDK that cannot execute, so
	 * the run ended at the exec rather than at the decision. The two halves
	 * of one run now give one answer.
	 *
	 * Unless JKITE_JDK_INDEX is set, which is the way out the warning used to
	 * point at: an index of musl builds is the caller's to supply, and having
	 * supplied one they are not to be told it cannot be done.
	 */
	static void requireAnInstallableJdk(Util.OS os, String indexOverride) {
		if (os != Util.OS.alpine_linux || (indexOverride != null && !indexOverride.trim().isEmpty())) {
			return;
		}
		throw new ExitException(ExitException.EXIT_UNEXPECTED_STATE,
				"There is no JDK to install for Alpine (musl): the ones jkite can download are built"
						+ " against glibc and will not run here. Install a JDK yourself and set"
						+ " JAVA_HOME, or set " + Settings.ENV_JDK_INDEX + " to an index of musl builds.");
	}

	private JdkIndex.Entry selectEntry(RequestedVersion version) {
		JdkIndex index = JdkIndex.instance();
		// For an open request we install a single major version rather than the
		// newest JDK in existence: the default version when it satisfies the
		// request, the requested major otherwise.
		if (version.isOpen()) {
			RequestedVersion preferred = RequestedVersion
				.ofMajor(Math.max(version.major(), defaultJavaVersion), false);
			Optional<JdkIndex.Entry> entry = index.find(preferred);
			if (entry.isPresent()) {
				return entry.get();
			}
			Util.verboseMsg("No JDK " + preferred + " available, looking for any " + version);
		}
		return index.find(version)
			.orElseThrow(() -> new ExitException(ExitException.EXIT_INVALID_INPUT,
					"No JDK matching version '" + version + "' is available for "
							+ JdkIndex.platform() + " from " + Settings.JDK_DISTRO));
	}

	private Jdk download(JdkIndex.Entry entry, Path jdkDir) {
		// named after this process, so that an installation running without a
		// lock (a filesystem that cannot lock) unpacks into a directory of its
		// own instead of into the one another run is unpacking into
		Path tmpDir = jdksDir.resolve(entry.version + "." + ProcessHandle.current().pid() + ".tmp");
		Path pkg = Settings.getCacheDir(Settings.CacheClass.urls)
			.resolve("bootstrap-jdk-" + entry.version + "." + entry.archiveType);
		Util.deletePath(tmpDir, true);
		Util.infoMsg("Downloading JDK " + entry.version + " (" + entry.distro
				+ "). Be patient, this can take several minutes...");
		Util.verboseMsg("Downloading " + entry.url);
		try {
			requireExpectedSource(entry);
			Downloader.download(entry.url, pkg);
			String verified = verifyChecksum(entry, pkg);
			Util.infoMsg("Installing JDK " + entry.version + "...");
			Unpacker.unpackJdk(pkg, tmpDir);
			if (!Jdk.resolveVersion(tmpDir).isPresent()) {
				throw new IOException("The JDK package does not seem to contain a valid JDK");
			}
			// into the tree before it is moved, so that a JDK in the cache is
			// never there without the record of what it was installed from
			InstallRecord.write(tmpDir, entry, verified);
			if (Jdk.of(jdkDir, Jdk.Origin.CACHE) == null) {
				Util.deletePath(jdkDir, true);
				Files.move(tmpDir, jdkDir);
			} else {
				// another run installed it while this one was downloading, and
				// its copy is as good as ours
				Util.verboseMsg("JDK " + entry.version + " was installed by another process, keeping that one");
			}
		} catch (IOException | RuntimeException e) {
			Util.deletePath(tmpDir, true);
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
					"Unable to download or install JDK version " + entry.version + " from " + entry.url
							+ " (" + e.getMessage() + ")",
					e);
		} finally {
			Util.deletePath(pkg, true);
		}
		Jdk jdk = Jdk.of(jdkDir, Jdk.Origin.CACHE);
		if (jdk == null) {
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR, "Failed to find JDK in: " + jdkDir);
		}
		return jdk;
	}

	/**
	 * Refuses an index entry that does not name an archive published by the
	 * distribution itself. {@link JdkSource} says what that means and why the
	 * checksum cannot be asked the same question.
	 */
	void requireExpectedSource(JdkIndex.Entry entry) throws IOException {
		JdkSource.requireIndexUrl(entry.url,
				"the JDK the JVM index names for " + entry.distro + " " + entry.version + ",");
	}

	/**
	 * Verifies the downloaded archive against the SHA-256 published next to it
	 * (".sha256.txt", which Temurin publishes for every archive). Both a
	 * mismatch and a checksum that cannot be read abort the installation: a
	 * checksum that is merely unreachable would otherwise be a way to have the
	 * archive accepted unverified.
	 *
	 * What this catches is an archive that arrived damaged or altered on the
	 * way. It is not what keeps a bad index out: the checksum is read from
	 * beside the archive, so whoever chooses the one chooses the other.
	 * {@link #requireExpectedSource} is what decides whose archive this is.
	 *
	 * @return the digest that was verified, for {@link InstallRecord}
	 */
	private String verifyChecksum(JdkIndex.Entry entry, Path pkg) throws IOException {
		Optional<String> published = Downloader.tryReadString(entry.url + ".sha256.txt");
		if (!published.isPresent()) {
			throw new IOException("No SHA-256 published next to " + entry.url
					+ ", so the download cannot be verified");
		}
		String expected = published.get().trim().split("\\s+")[0].toLowerCase();
		String actual = Util.sha256(pkg);
		if (!expected.equals(actual)) {
			Util.deletePath(pkg, true);
			throw new IOException("SHA-256 mismatch for " + entry.url
					+ ": expected " + expected + " but got " + actual);
		}
		Util.verboseMsg("SHA-256 verified: " + actual);
		return actual;
	}

	private Jdk finish(Jdk jdk) {
		installed = null;
		return jdk;
	}

	/** Maps a JRE folder inside a JDK to the JDK's home. */
	private static Path jre2jdk(Path jdkHome) {
		if (!Files.isRegularFile(jdkHome.resolve("release"))) {
			Path jh = jdkHome.toAbsolutePath();
			try {
				jh = jh.toRealPath();
			} catch (IOException e) {
				// ignore
			}
			if (jh.endsWith("jre") && Files.isRegularFile(jh.getParent().resolve("release"))) {
				return jh.getParent();
			}
		}
		return jdkHome;
	}

}
