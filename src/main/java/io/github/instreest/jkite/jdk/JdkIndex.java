package io.github.instreest.jkite.jdk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import dev.jbang.ExitException;
import io.github.instreest.jkite.Settings;
import io.github.instreest.jkite.dependencies.DependencyResolver;
import io.github.instreest.jkite.util.RequestedVersion;
import dev.jbang.util.Util;

/**
 * The list of downloadable JDKs. jkite uses the JVM index that the
 * Coursier project publishes to Maven Central as
 * <code>io.get-coursier.jvm.indices:index-&lt;platform&gt;</code>, so no
 * separate discovery service has to be reachable: the index travels over the
 * same Maven repository (and therefore the same mirrors, proxies and
 * credentials) that jkite already needs for <code>//DEPS</code>.
 *
 * The index maps a distribution and version to the distributor's own download
 * URL, for example
 * <code>temurin -&gt; 25.0.3 -&gt; tgz+https://github.com/adoptium/...tar.gz</code>.
 *
 * The version asked for is a range with no upper bound, so every run that has
 * to download a JDK takes the newest index published. That is the point: a JDK
 * released after this version of jkite is then installable without jkite
 * having to be updated, which is what lets a user ignore JDKs entirely. The
 * cost is that the list is not something this project pins, and it comes from
 * a project jkite does not control - so nothing here takes the index's word
 * for where a download comes from. That is
 * {@link io.github.instreest.jkite.jdk.JdkManager}'s check against the
 * distribution's own account, and it is what makes the open range affordable.
 */
public final class JdkIndex {
	static final String INDEX_GROUP_ID = "io.get-coursier.jvm.indices";
	static final String INDEX_VERSION_RANGE = "[0,)";
	static final String INDEX_ENTRY_PREFIX = "coursier/jvm/indices/v1/";
	/** See {@link #readAtMost}: forty times the real thing, and still nothing. */
	static final int MAX_INDEX_BYTES = 16 * 1024 * 1024;

	/** One downloadable JDK. */
	public static final class Entry {
		public final String distro;
		public final String version;
		public final String archiveType;
		public final String url;

		Entry(String distro, String version, String archiveType, String url) {
			this.distro = distro;
			this.version = version;
			this.archiveType = archiveType;
			this.url = url;
		}

		@Override
		public String toString() {
			return distro + " " + version + " (" + url + ")";
		}
	}

	private final String platform;
	private final JsonObject index;

	private static JdkIndex cached;

	private JdkIndex(String platform, JsonObject index) {
		this.platform = platform;
		this.index = index;
	}

	/**
	 * Loads (and caches) the index for the current platform. The environment
	 * variable JKITE_JDK_INDEX overrides where it comes from: it is either a
	 * path to a JSON file in the same format or a Maven coordinate.
	 */
	public static JdkIndex instance() {
		if (cached == null) {
			String platform = platform();
			cached = new JdkIndex(platform, read(platform));
		}
		return cached;
	}

	private static JsonObject read(String platform) {
		String override = System.getenv(Settings.ENV_JDK_INDEX);
		String source;
		String json;
		try {
			if (override != null && !override.trim().isEmpty()) {
				Path file = Paths.get(override.trim());
				if (Files.isRegularFile(file)) {
					source = file.toString();
					json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
				} else {
					source = override.trim();
					json = readFromJar(DependencyResolver.resolveArtifact(source), platform);
				}
			} else {
				source = INDEX_GROUP_ID + ":index-" + platform + ":" + INDEX_VERSION_RANGE;
				Util.verboseMsg("Resolving JDK index: " + source);
				json = readFromJar(DependencyResolver.resolveArtifact(source), platform);
			}
		} catch (IOException e) {
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
					"Could not read the JDK index for " + platform + ": " + e.getMessage(), e);
		}
		Util.verboseMsg("Using JDK index: " + source);
		return parse(json, source);
	}

	/** An index read from somewhere else than the configured source. */
	static JdkIndex of(String platform, String json) {
		return new JdkIndex(platform, parse(json, "<given>"));
	}

	static JsonObject parse(String json, String source) {
		try {
			JsonElement parsed = JsonParser.parseString(json);
			if (!parsed.isJsonObject()) {
				throw new JsonSyntaxException("not a JSON object");
			}
			return parsed.getAsJsonObject();
		} catch (JsonSyntaxException e) {
			throw new ExitException(ExitException.EXIT_UNEXPECTED_STATE,
					"The JDK index read from " + source + " is not valid: " + e.getMessage(), e);
		}
	}

	private static String readFromJar(Path jar, String platform) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(INDEX_ENTRY_PREFIX + platform + ".json");
			if (entry == null) {
				throw new IOException("No index for " + platform + " in " + jar);
			}
			try (InputStream is = zip.getInputStream(entry)) {
				return readAtMost(is, MAX_INDEX_BYTES, jar);
			}
		}
	}

	/**
	 * Reads an entry, refusing one that does not stop.
	 *
	 * A zip says how long an entry is and does not have to be telling the
	 * truth, so the length is taken from the bytes that arrive rather than
	 * from the header. What is being read here is a file this project does not
	 * publish, fetched at an open version range, and jkite would otherwise go
	 * on reading it until the JVM ran out of memory. The index for a platform
	 * is around 360 kB today and compresses about fifteen to one, so the limit
	 * is some forty times the real thing: large enough not to be reached by a
	 * list that grows, small enough to be nothing.
	 */
	static String readAtMost(InputStream is, int limit, Path source) throws IOException {
		byte[] buffer = new byte[8192];
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		int n;
		while ((n = is.read(buffer)) > 0) {
			if (out.size() + n > limit) {
				throw new IOException("The JDK index in " + source + " is longer than " + limit
						+ " bytes, which no real index is; refusing to read the rest of it");
			}
			out.write(buffer, 0, n);
		}
		return new String(out.toByteArray(), StandardCharsets.UTF_8);
	}

	/** The index name of the current platform, e.g. "linux-amd64". */
	public static String platform() {
		Util.OS os = Util.getOS();
		String osName;
		switch (os) {
		case linux:
			osName = "linux";
			break;
		case alpine_linux:
			// Reached only with JKITE_JDK_INDEX set: JdkManager refuses to
			// install here otherwise, because the published index holds glibc
			// builds and they do not run on musl. An index the caller supplied
			// is keyed the same way, so the platform name is still the linux
			// one. No warning - the caller answered this already.
			osName = "linux";
			break;
		case mac:
			osName = "darwin";
			break;
		case windows:
			osName = "windows";
			break;
		default:
			throw new ExitException(ExitException.EXIT_UNEXPECTED_STATE,
					"No JDKs can be downloaded for this operating system: " + os);
		}
		Util.Arch arch = Util.getArch();
		String archName;
		switch (arch) {
		case x64:
			archName = "amd64";
			break;
		case x32:
			archName = "x86";
			break;
		case aarch64:
		case arm64:
			archName = "arm64";
			break;
		case arm:
			archName = "arm";
			break;
		case ppc64le:
			archName = "ppc64le";
			break;
		case s390x:
			archName = "s390x";
			break;
		case riscv64:
			archName = "riscv64";
			break;
		default:
			throw new ExitException(ExitException.EXIT_UNEXPECTED_STATE,
					"No JDKs can be downloaded for this architecture: " + arch);
		}
		return osName + "-" + archName;
	}


	/**
	 * The newest version satisfying the request, looking at each configured
	 * distribution in turn.
	 */
	public Optional<Entry> find(RequestedVersion version) {
		// jkite installs from one distribution and offers no knob for it
		for (String distro : Collections.singletonList(Settings.JDK_DISTRO)) {
			Optional<Entry> found = find(distro, version);
			if (found.isPresent()) {
				return found;
			}
			Util.verboseMsg("No JDK " + version + " for " + platform + " in distribution '" + distro + "'");
		}
		return Optional.empty();
	}

	Optional<Entry> find(String distro, RequestedVersion version) {
		Map<String, String> versions = versionsOf(distro);
		String bestVersion = null;
		String bestValue = null;
		for (Map.Entry<String, String> e : versions.entrySet()) {
			if (!version.matches(e.getKey())) {
				continue;
			}
			if (bestVersion == null || RequestedVersion.compare(RequestedVersion.componentsOf(e.getKey()),
					RequestedVersion.componentsOf(bestVersion)) > 0) {
				bestVersion = e.getKey();
				bestValue = e.getValue();
			}
		}
		if (bestVersion == null) {
			return Optional.empty();
		}
		// values have the form "<archive type>+<url>", e.g. "tgz+https://..."
		int sep = bestValue.indexOf('+');
		if (sep < 0) {
			Util.warnMsg("Unexpected JDK index entry for " + distro + " " + bestVersion + ": " + bestValue);
			return Optional.empty();
		}
		return Optional.of(new Entry(distro, bestVersion, bestValue.substring(0, sep), bestValue.substring(sep + 1)));
	}

	/**
	 * The versions the index lists for a distribution, mapped to their
	 * "&lt;archive type&gt;+&lt;url&gt;" value. Anything shaped differently is
	 * skipped rather than rejected: an index that grows an entry of another kind
	 * must not stop the ones we do understand from being used.
	 */
	private Map<String, String> versionsOf(String distro) {
		JsonElement entry = index.get(distro);
		if (entry == null || !entry.isJsonObject()) {
			return Collections.emptyMap();
		}
		Map<String, String> versions = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> e : entry.getAsJsonObject().entrySet()) {
			JsonElement value = e.getValue();
			if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
				versions.put(e.getKey(), value.getAsString());
			}
		}
		return versions;
	}
}
