package io.github.instreest.jkite.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.jbang.ExitException;
import io.github.instreest.jkite.Settings;
import io.github.instreest.jkite.Version;
import io.github.instreest.jkite.dependencies.ArtifactInfo;
import io.github.instreest.jkite.dependencies.DependencyResolver;
import dev.jbang.dependencies.MavenRepo;
import io.github.instreest.jkite.jdk.Jdk;
import io.github.instreest.jkite.jdk.JdkManager;
import io.github.instreest.jkite.spi.Attribute;
import io.github.instreest.jkite.spi.Providers;
import io.github.instreest.jkite.spi.SourceDirectives;
import dev.jbang.util.JavaUtil;
import io.github.instreest.jkite.util.OsDetector;
import io.github.instreest.jkite.util.Placeholders;
import io.github.instreest.jkite.util.RealPath;
import dev.jbang.util.Util;

/**
 * Everything known about a script: its sources, the resources, dependencies,
 * options and settings gathered from the <code>//</code>-directives of the main
 * file and of every file it pulls in with <code>//SOURCES</code>.
 *
 * The directives are read through {@link io.github.instreest.jkite.spi.DirectiveParser}, the
 * interface JBang sits behind, and are applied here with the same rules JBang
 * uses: the main class comes from the main file only, everything else
 * accumulates over all files. Directives jkite has no use for (//MODULE,
 * //CDS, //JAVAAGENT, //GAV, //DESCRIPTION, //DOCS) are parsed and ignored: the
 * interface mirrors what JBang understands, not what jkite acts on.
 *
 * The build output goes to
 * <code>$JKITE_CACHE_DIR/jars/&lt;file&gt;.&lt;hash&gt;/&lt;base&gt;.jar</code>,
 * where the hash is the one {@link #getStableId()} computes: it covers the
 * bytes of every source and resource and everything else that decides what the
 * build does, so a change in any of them triggers a rebuild.
 */
public class Project {
	public static final String ATTR_ADD_EXPORTS = "Add-Exports";
	public static final String ATTR_ADD_OPENS = "Add-Opens";
	public static final String ATTR_ENABLE_NATIVE_ACCESS = "Enable-Native-Access";

	/** A file to copy into the jar, as declared by <code>//FILES</code>. */
	public static final class FileRef {
		private final Path source;
		private final Path target;

		FileRef(Path source, Path target) {
			this.source = source;
			this.target = target;
		}

		public Path getSource() {
			return source;
		}

		/** The path this file gets inside the jar. */
		public Path entryName() {
			return target != null ? target : source.getFileName();
		}

		public Path to(Path parent) {
			Path to = parent.resolve(entryName()).normalize();
			if (!to.startsWith(parent.normalize())) {
				throw new ExitException(ExitException.EXIT_INVALID_INPUT,
						"Refusing to write outside " + parent + ": " + to);
			}
			return to;
		}

		void copy(Path destroot) {
			Path to = to(destroot);
			Util.verboseMsg("Copying " + source + " to " + to);
			try {
				Files.createDirectories(to.getParent());
				Files.copy(source, to, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				throw new ExitException(ExitException.EXIT_UNEXPECTED_STATE,
						"Could not copy " + source + " to " + to, e);
			}
		}
	}

	private final Path mainSource;
	private final Set<Path> sources = new LinkedHashSet<>();
	private final List<FileRef> resources = new ArrayList<>();
	private final Set<String> dependencies = new LinkedHashSet<>();
	private final List<MavenRepo> repositories = new ArrayList<>();
	private final List<String> compileOptions = new ArrayList<>();
	private final List<String> runtimeOptions = new ArrayList<>();
	private final Map<String, String> manifestAttributes = new LinkedHashMap<>();
	private final Map<String, String> properties;
	private final Properties contextProperties;

	private String javaVersion;
	private String mainClass;
	/** //MAIN as the directives gave it, which setMainClass does not change. */
	private String declaredMainClass;
	private boolean enablePreview;

	private JdkManager jdkManager;

	// cached values
	private String stableId;
	private List<ArtifactInfo> classPath;
	private Jdk jdk;

	/**
	 * Reads the script and everything its directives pull in. The properties
	 * are the -Dkey=value ones, used for <code>${...}</code> substitution in the
	 * directives and passed on to the script.
	 */
	public Project(Path mainSource, Map<String, String> properties) {
		this.mainSource = RealPath.of(mainSource, "The script");
		this.properties = properties;
		this.contextProperties = new Properties(System.getProperties());
		OsDetector.detect(contextProperties);
		contextProperties.putAll(properties);
		addSource(this.mainSource, true);
	}

	private String replaceProperties(String item) {
		return Placeholders.replace(item, contextProperties);
	}

	private Function<String, String> propertyReplacer() {
		return this::replaceProperties;
	}

	/**
	 * Reads a source file and applies its directives, then does the same for
	 * every file it names with //SOURCES.
	 */
	private void addSource(Path source, boolean main) {
		if (!sources.add(source)) {
			return;
		}
		if (!Files.isReadable(source)) {
			throw new ExitException(ExitException.EXIT_INVALID_INPUT,
					"Source file could not be found or read: " + source);
		}
		SourceDirectives directives = Providers.directiveParser()
			.parse(Util.readString(source), propertyReplacer());
		Path baseDir = source.getParent();

		if (main) {
			mainClass = directives.mainMethod();
			declaredMainClass = mainClass;
			enablePreview = directives.enablePreview();
			// as JBang does for Java sources, so that debugging and named
			// parameters keep working
			compileOptions.add("-g");
			compileOptions.add("-parameters");
		}

		dependencies.addAll(directives.binaryDependencies());
		addRepositories(directives.repositories());
		compileOptions.addAll(directives.compileOptions());
		runtimeOptions.addAll(directives.runtimeOptions());
		directives.manifestOptions().forEach(this::putManifestAttribute);
		resources.addAll(toFileRefs(directives.fileRefs(baseDir), baseDir));

		String version = directives.javaVersion();
		if (version != null && JavaUtil.checkRequestedVersion(version)
				&& new JavaUtil.RequestedVersionComparator().compare(javaVersion, version) > 0) {
			javaVersion = version;
		}

		for (String pattern : directives.sources()) {
			List<String> files = Util.explode(null, baseDir, pattern);
			if (files.isEmpty()) {
				Util.warnMsg("//SOURCES " + pattern + " (in " + source.getFileName() + ") matched no files");
			}
			for (String f : files) {
				addSource(RealPath.of(baseDir.resolve(f), "The source named by //SOURCES"), false);
			}
		}
	}

	private void putManifestAttribute(Attribute attribute) {
		if (!attribute.key().isEmpty()) {
			manifestAttributes.put(attribute.key(), attribute.value() != null ? attribute.value() : "true");
		}
	}

	/** Turns //FILES entries (with globs and optional aliases) into copy jobs. */
	private List<FileRef> toFileRefs(List<String> files, Path baseDir) {
		return files.stream()
			.map(ref -> {
				String[] split = ref.split("=", 2);
				String src = split.length == 1 ? split[0] : split[1];
				String dest = split.length == 1 ? null : split[0];
				Path target = dest != null && !dest.isEmpty() ? Paths.get(dest) : null;
				// Not isAbsolute(): on Windows "/etc/x" has a root but no drive, so
				// it is not absolute, and it would still name a place from the
				// root of the drive the run happens to be on.
				if (target != null && target.getRoot() != null) {
					throw new ExitException(ExitException.EXIT_INVALID_INPUT,
							"Only relative paths allowed in //FILES. Found: " + dest);
				}
				// The target names a place inside the jar, so leaving it has no
				// meaning to begin with; without this it would name a place on
				// the machine instead.
				if (target != null && target.normalize().startsWith("..")) {
					throw new ExitException(ExitException.EXIT_INVALID_INPUT,
							"//FILES target must stay inside the jar. Found: " + dest);
				}
				Path from = RealPath.of(baseDir.resolve(src), "The file named by //FILES");
				if (!Files.isReadable(from)) {
					throw new ExitException(ExitException.EXIT_INVALID_INPUT,
							"File could not be found or read: " + from);
				}
				if (target != null && dest.endsWith("/")) {
					target = target.resolve(from.getFileName());
				}
				return new FileRef(from, target);
			})
			.collect(Collectors.toList());
	}

	// ------------------------------------------------------------- accessors

	public Path getMainSource() {
		return mainSource;
	}

	public List<Path> getSources() {
		return new ArrayList<>(sources);
	}

	public List<FileRef> getResources() {
		return Collections.unmodifiableList(resources);
	}

	public List<String> getDependencies() {
		return new ArrayList<>(dependencies);
	}

	public List<MavenRepo> getRepositories() {
		return Collections.unmodifiableList(repositories);
	}

	public void addRepositories(List<MavenRepo> repos) {
		repos.forEach(this::addRepository);
	}

	private void addRepository(MavenRepo repo) {
		if (repositories.stream().noneMatch(r -> r.getId().equals(repo.getId()))) {
			repositories.add(repo);
		}
	}

	public List<String> getCompileOptions() {
		return Collections.unmodifiableList(compileOptions);
	}

	public List<String> getRuntimeOptions() {
		return Collections.unmodifiableList(runtimeOptions);
	}

	public Map<String, String> getManifestAttributes() {
		return manifestAttributes;
	}

	public Map<String, String> getProperties() {
		return properties;
	}

	/** Requested Java version like "17" or "17+", or null. */
	public String getJavaVersion() {
		return javaVersion;
	}

	public String getMainClass() {
		return mainClass;
	}

	public void setMainClass(String mainClass) {
		this.mainClass = mainClass;
	}

	public boolean enablePreview() {
		return enablePreview;
	}

	public void setJdkManager(JdkManager jdkManager) {
		this.jdkManager = jdkManager;
	}

	public Jdk getJdk() {
		if (jdk == null) {
			if (jdkManager == null) {
				jdkManager = new JdkManager();
			}
			jdk = jdkManager.getOrInstallJdk(javaVersion);
		}
		return jdk;
	}

	/** The resolved dependencies, jars on disk. */
	public List<ArtifactInfo> resolveClassPath() {
		if (classPath == null) {
			classPath = new DependencyResolver()
				.addRepositories(repositories)
				.addDependencies(getDependencies())
				.resolve();
		}
		return classPath;
	}

	/** Class path of the dependencies only, without this project's own jar. */
	public String getDependencyClassPath() {
		return resolveClassPath().stream()
			.map(a -> a.getFile().toAbsolutePath().toString())
			.distinct()
			.collect(Collectors.joining(Settings.CP_SEPARATOR));
	}

	/**
	 * Names the build this project would produce: the same id means the same
	 * jar, so a jar found under it can be used as it is.
	 *
	 * It covers the bytes of every source and resource, the name each resource
	 * gets inside the jar, and everything else that decides what javac and the
	 * packaging do: the compile options, the requested Java version, the main
	 * class, the manifest entries and jkite's own version. Those come from
	 * directives, which are in the sources already, but a <code>${...}</code>
	 * in one of them resolves against the properties and the environment, so
	 * the same text can mean two different builds. What the build is made of is
	 * the resolved value, so that is what is hashed.
	 *
	 * Files are hashed as bytes, one at a time: a resource that is not text is
	 * not flattened into replacement characters first, and a large one is not
	 * read into memory to be hashed.
	 */
	public String getStableId() {
		if (stableId == null) {
			MessageDigest digest = newDigest();
			// what the build is, beyond the files it is made from
			update(digest, "jkite", Version.current());
			update(digest, "java", javaVersion);
			update(digest, "preview", Boolean.toString(enablePreview));
			update(digest, "main", declaredMainClass);
			compileOptions.forEach(option -> update(digest, "option", option));
			manifestAttributes.forEach((key, value) -> update(digest, "manifest", key + "=" + value));
			// What the dependencies resolved to, not the //DEPS lines - those are
			// in the source bytes below already. What is not is what they resolve
			// to, and that moves while the script does not: a coordinate
			// republished, a snapshot, a version a BOM manages. The jar is
			// compiled against that class path and then run against whatever is
			// resolved next time, so without this a jar built against one set of
			// classes is handed a different set to run on, and the mismatch shows
			// up as a NoSuchMethodError rather than as a rebuild.
			resolveClassPath().forEach(artifact -> update(digest, "dependency",
					artifact.getCoordinate() + " " + artifact.getFingerprint()));
			// the files, by content rather than by the text they decode to
			sources.forEach(src -> update(digest, "source", src.getFileName() + " " + contentHash(src)));
			resources.forEach(res -> update(digest, "resource",
					res.entryName() + " " + contentHash(res.getSource())));
			stableId = hex(digest.digest());
		}
		return stableId;
	}

	private static MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new ExitException(ExitException.EXIT_INTERNAL_ERROR, e);
		}
	}

	/**
	 * Adds one named value to the id. The name and a separator go in with it,
	 * so that two different lists of values cannot add up to the same bytes.
	 */
	private static void update(MessageDigest digest, String name, String value) {
		String text = value != null ? value : "";
		String entry = name + " " + text.length() + " " + text + "\n";
		digest.update(entry.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * The SHA-256 of a file's bytes. A file that cannot be read gets a marker
	 * instead: the build that follows will fail on it and say so, which is a
	 * better answer than refusing to name the build at all.
	 */
	private static String contentHash(Path file) {
		try {
			return Util.sha256(file);
		} catch (IOException | RuntimeException e) {
			Util.verboseMsg("Could not read " + file + " while naming the build: " + e);
			return "unreadable";
		}
	}

	private static String hex(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	public Path getBuildDir() {
		return Settings.getCacheDir(Settings.CacheClass.jars)
			.resolve(inAscii(mainSource.getFileName().toString()) + "." + getStableId());
	}

	public Path getJarFile() {
		return getBuildDir().resolve(inAscii(Util.getBaseName(mainSource.getFileName().toString())) + ".jar");
	}

	/**
	 * The script's name, reduced to characters a path can carry anywhere.
	 *
	 * jkite hands these paths to javac and to java, and on Windows both are
	 * launchers that convert their command line to the machine's ANSI code
	 * page. A character outside that page arrives as a question mark, which is
	 * not something a Windows path may contain at all, and the run ends at
	 * "Error during compile" with a stack trace from WindowsPath.parse. A
	 * script called レポート.java on an English Windows is enough: measured on
	 * a CI runner in code page 437, where it fails and the same script with an
	 * ASCII name does not.
	 *
	 * The name is here to be read, not to identify anything - the hash beside
	 * it already does that, and it covers the source bytes, so two scripts
	 * whose names reduce to the same thing still get their own directory. So
	 * reducing it costs nothing and is done on every platform rather than on
	 * the one that needs it, because a cache that changes shape by platform is
	 * a worse thing to reason about than a name with underscores in it.
	 *
	 * What is kept is every printable ASCII character a filename may hold on
	 * both POSIX and Windows. That is deliberately wider than it needs to be:
	 * any name that works today is left exactly as it is, so no cache is
	 * invalidated and nothing is rebuilt for the sake of this.
	 */
	static String inAscii(String name) {
		StringBuilder out = new StringBuilder(name.length());
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			out.append(canBeInAName(c) ? c : '_');
		}
		return out.toString();
	}

	private static boolean canBeInAName(char c) {
		if (c < 0x20 || c >= 0x7f) {
			return false;
		}
		// what Windows reserves; the separators are impossible in a name
		// anywhere, and the rest it refuses outright
		return "<>:\"/\\|?*".indexOf(c) < 0;
	}

	public Path getCompileDir() {
		return getBuildDir().resolve("classes");
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Project && mainSource.equals(((Project) o).mainSource);
	}

	@Override
	public int hashCode() {
		return Objects.hash(mainSource);
	}
}
