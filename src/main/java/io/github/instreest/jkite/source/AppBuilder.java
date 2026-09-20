package io.github.instreest.jkite.source;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.jbang.ExitException;
import io.github.instreest.jkite.util.UsablePaths;
import io.github.instreest.jkite.dependencies.ArtifactInfo;
import io.github.instreest.jkite.jdk.Jdk;
import dev.jbang.util.CommandBuffer;
import dev.jbang.util.JavaUtil;
import io.github.instreest.jkite.util.Fingerprint;
import io.github.instreest.jkite.util.MainClassFinder;
import dev.jbang.util.Util;

/**
 * Compiles a {@link Project} with the JDK's <code>javac</code> and packages the
 * result into a jar, applying the directives that affect the build:
 * <code>//COMPILE_OPTIONS</code>, <code>//FILES</code>, <code>//MANIFEST</code>,
 * <code>//MAIN</code> and <code>//PREVIEW</code>.
 *
 * An existing jar is reused when nothing the build depends on has changed (the
 * build directory name contains a hash of it, see {@link Project#getStableId()}),
 * the dependencies are still present and it was built with a JDK that satisfies
 * the requested version - and, for //PREVIEW, with exactly the JDK that will
 * run it.
 */
public class AppBuilder {
	public static final String ATTR_BUILD_JDK = "Build-Jdk";

	/** The time every entry in a built jar carries, so that the jar is the
	 * same file whenever it is built from the same inputs. */
	private static final LocalDateTime PACKAGED_AT = LocalDateTime.of(2000, 1, 1, 0, 0, 0);

	private final Project project;

	public AppBuilder(Project project) {
		this.project = project;
	}

	/** Builds the project and returns the jar. */
	public Path build() throws IOException {
		checkTheseCanReachJavac();
		Path jar = project.getJarFile();
		if (!Util.isFresh() && isUpToDate(jar)) {
			Util.verboseMsg("No build required. Reusing jar from " + jar);
			return jar;
		}
		Files.createDirectories(project.getBuildDir());
		// Compile into a directory of our own so that concurrent builds of the
		// same script cannot delete each other's classes. The jar itself is
		// written atomically, so whichever build finishes last wins.
		Path compileDir = keepClasses()
				? project.getCompileDir()
				: Files.createTempDirectory(project.getBuildDir(), "classes-");
		Util.deletePath(compileDir, true);
		Files.createDirectories(compileDir);
		try {
			compile(compileDir);
			copyResources(compileDir);
			findMain(compileDir);
			createJar(compileDir, jar);
		} finally {
			if (!keepClasses()) {
				Util.deletePath(compileDir, true);
			}
		}
		return jar;
	}

	/** Keeps the compiled classes around for inspection. */
	static boolean keepClasses() {
		return "true".equals(System.getProperty("jkite.build.keepclasses"));
	}

	private boolean isUpToDate(Path jar) {
		if (!Files.isReadable(jar)) {
			Util.verboseMsg("Build required as " + jar + " not readable or not found.");
			return false;
		}
		if (!isTheJarThatWasBuilt(jar)) {
			return false;
		}
		if (!project.resolveClassPath().stream().allMatch(ArtifactInfo::isUpToDate)) {
			Util.verboseMsg("Building as previously built jar found but its dependencies are not up-to-date.");
			return false;
		}
		try (JarFile jf = new JarFile(jar.toFile())) {
			Attributes attrs = jf.getManifest() != null ? jf.getManifest().getMainAttributes() : null;
			String buildJdk = attrs != null ? attrs.getValue(ATTR_BUILD_JDK) : null;
			String reason = cannotReuse(buildJdk, project.getJavaVersion(),
					() -> project.getJdk().majorVersion(), project.enablePreview());
			if (reason != null) {
				Util.verboseMsg("Building as " + reason + ".");
				return false;
			}
			if (project.getMainClass() == null) {
				project.setMainClass(attrs.getValue(Attributes.Name.MAIN_CLASS));
			}
			return true;
		} catch (IOException e) {
			Util.verboseMsg("Building as previously built jar could not be read: " + e);
			return false;
		}
	}

	/**
	 * Looks at the paths before javac and java are asked to, because neither
	 * of them says which path was the problem.
	 *
	 * The build directory covers the compile directory and the jar, which are
	 * inside it, and it is checked as a class path entry because the jar goes
	 * on one. The sources only ever go on a command line as arguments of their
	 * own, so a separator in one of those is harmless - measured, not assumed -
	 * and they are checked for the encoding alone.
	 */
	private void checkTheseCanReachJavac() {
		UsablePaths.requireClassPathEntry(project.getBuildDir(), "the cache directory (JKITE_DIR)");
		for (Path source : project.getSources()) {
			UsablePaths.requireUsable(source, "the script");
		}
		for (ArtifactInfo artifact : project.resolveClassPath()) {
			UsablePaths.requireClassPathEntry(artifact.getFile(),
					"the dependency " + artifact.getCoordinate());
		}
	}

	/** Where the fingerprint of a built jar is kept, next to the jar. */
	private static Path fingerprintFile(Path jar) {
		return jar.resolveSibling(jar.getFileName() + ".id");
	}

	/**
	 * True when the jar is the one this build directory says it is.
	 *
	 * The directory is named after the inputs, so a jar in it is the jar those
	 * inputs produce - as long as it is still the file that was put there. A
	 * jar the build never finished writing cannot end up here (it is renamed
	 * into place), but a cache lives on a disk for months and is written to by
	 * whoever has the account. The fingerprint is what says so, and it is
	 * cheap: these jars hold a few classes.
	 */
	private boolean isTheJarThatWasBuilt(Path jar) {
		Path file = fingerprintFile(jar);
		Fingerprint expected = null;
		try {
			expected = Files.isReadable(file) ? Fingerprint.parse(Util.readString(file).trim()) : null;
		} catch (RuntimeException e) {
			Util.verboseMsg("Could not read " + file + ": " + e);
		}
		if (expected == null) {
			// jars built before this was written down, and jars whose
			// fingerprint was lost, are built once more and get one
			Util.verboseMsg("Building as " + jar + " has no fingerprint to check it against.");
			return false;
		}
		if (!expected.matches(jar)) {
			Util.warnMsg("The cached " + jar + " is not the jar that was built there. Building it again.");
			return false;
		}
		return true;
	}

	/**
	 * Why a jar built with <code>buildJdk</code> cannot serve a run that asks
	 * for <code>requested</code>, or null when it can. Told apart from the jar
	 * it was read out of because this is the part with the arithmetic in it.
	 *
	 * <code>currentJdk</code> is asked for rather than given, and only once the
	 * requested version has had its say: finding out which JDK is here can mean
	 * installing one, and a jar that is out of date on its version alone is out
	 * of date whichever JDK would have run it.
	 */
	static String cannotReuse(String buildJdk, String requested, IntSupplier currentJdk, boolean preview) {
		if (buildJdk == null) {
			return "previously built jar found but it has incomplete meta data";
		}
		int built = JavaUtil.parseJavaVersion(buildJdk);
		if (!JavaUtil.satisfiesRequestedVersion(requested, built)) {
			return "the jar was built with Java " + built
					+ " which does not satisfy the requested version " + requested;
		}
		int current = currentJdk.getAsInt();
		if (current < built) {
			return "the jar was built with Java " + built
					+ " which is newer than the JDK available now";
		}
		// A class file that uses preview features is only loadable by the JVM of
		// exactly the version that compiled it, so for //PREVIEW a newer JDK is
		// not good enough: the jar would not start at all.
		if (preview && current != built) {
			return "the jar was built with the preview features of Java " + built
					+ ", which Java " + current + " does not load";
		}
		return null;
	}

	/**
	 * What javac is asked to do. Separated from running it so that the options
	 * can be read back: several of them are there for a reason that is not
	 * visible in the result of a successful compile.
	 */
	List<String> compileCommand(Path compileDir, Jdk jdk) {
		List<String> cmd = new ArrayList<>();
		cmd.add(jdk.javacCmd());
		if (project.enablePreview()) {
			cmd.add("--enable-preview");
			cmd.add("-source");
			cmd.add(Integer.toString(jdk.majorVersion()));
		}
		// The directives were read as UTF-8, so javac has to read the same file
		// the same way. Left to itself it uses the platform's default charset,
		// which is UTF-8 on a recent JDK but not on an older one and not on a
		// Windows machine with a legacy code page - and then the same source
		// builds differently on two machines, a string literal or an identifier
		// coming out mangled rather than as an error anyone would see. Before
		// the script's own options, because javac takes the last -encoding it
		// is given: a script that means another encoding can still say so.
		cmd.addAll(Arrays.asList("-encoding", "UTF-8"));
		cmd.addAll(project.getCompileOptions());
		String cp = project.getDependencyClassPath();
		if (!cp.isEmpty()) {
			cmd.addAll(Arrays.asList("-classpath", cp));
		}
		cmd.addAll(Arrays.asList("-d", compileDir.toAbsolutePath().toString()));
		// The sources this project declares are the whole of it. Without a
		// source path javac falls back on the class path, and without a class
		// path on the directory the run started in, and compiles whatever .java
		// it finds there for a class it cannot otherwise resolve - into the jar,
		// but not into the build id, which only covers what the script declares.
		// A jar would then be reused after such a file changed, and two projects
		// whose scripts happen to match would share one.
		//
		// The compile directory is the source path because it is a directory
		// that exists and holds no sources: it is made empty just before this
		// runs, and //FILES are copied in afterwards. An empty argument would
		// say the same thing, but it is dropped on the way to javac when the
		// command line is long enough to go through an @-file.
		cmd.addAll(Arrays.asList("-sourcepath", compileDir.toAbsolutePath().toString()));
		cmd.addAll(project.getSources().stream().map(Path::toString).collect(Collectors.toList()));
		return cmd;
	}

	private void compile(Path compileDir) throws IOException {
		List<String> cmd = compileCommand(compileDir, project.getJdk());

		Util.infoMsg("Building jar for " + project.getMainSource().getFileName() + "...");
		Util.verboseMsg("Compile: " + String.join(" ", cmd));
		ProcessBuilder pb = CommandBuffer.of(cmd).applyWindowsMaxProcessLimit().asProcessBuilder().inheritIO();
		Process process = pb.start();
		try {
			process.waitFor();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR, e);
		}
		if (process.exitValue() != 0) {
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR, "Error during compile" + sourcesHint());
		}
	}

	/**
	 * Added to a failed compile when the script names no other source. javac has
	 * just been told not to go looking for one, so a class that lives in a file
	 * beside the script is a "cannot find symbol" rather than something quietly
	 * compiled in, and the line that fixes it is not in javac's output.
	 */
	private String sourcesHint() {
		return project.getSources().size() > 1
				? ""
				: ". If a class it uses lives in another file, name that file with //SOURCES";
	}

	/** Copies the //FILES entries next to the classes so they end up in the jar. */
	private void copyResources(Path compileDir) {
		project.getResources().forEach(r -> r.copy(compileDir));
	}

	private void findMain(Path compileDir) throws IOException {
		if (project.getMainClass() != null) {
			return;
		}
		List<String> mains = MainClassFinder.findMainClasses(compileDir);
		if (mains.size() > 1) {
			// prefer the class named like the script file
			String suggested = Util.getBaseName(project.getMainSource().getFileName().toString());
			List<String> preferred = mains.stream()
				.filter(m -> m.equals(suggested) || m.endsWith("." + suggested))
				.collect(Collectors.toList());
			if (!preferred.isEmpty()) {
				mains = preferred;
			}
		}
		if (!mains.isEmpty()) {
			project.setMainClass(mains.get(0));
			if (mains.size() > 1) {
				Util.warnMsg("Could not locate unique main() method. Use //MAIN to name the main class. "
						+ "Falling back to use first found: " + String.join(",", mains));
			}
		}
	}

	private void createJar(Path compileDir, Path jar) throws IOException {
		Manifest manifest = new Manifest();
		Attributes attrs = manifest.getMainAttributes();
		attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
		project.getManifestAttributes().forEach(attrs::putValue);
		// the full version, so that a pinned request can be checked against a
		// previously built jar
		attrs.putValue(ATTR_BUILD_JDK, project.getJdk().version());
		if (project.getMainClass() != null) {
			attrs.put(Attributes.Name.MAIN_CLASS, project.getMainClass());
		}
		Util.verboseMsg("Package: " + jar);
		Files.createDirectories(jar.getParent());
		// a temporary file of our own, so that concurrent builds of the same
		// script do not write into each other's jar
		Path tmp = Files.createTempFile(jar.getParent(), jar.getFileName().toString(), ".tmp");
		try {
			try (OutputStream os = Files.newOutputStream(tmp); JarOutputStream jos = new JarOutputStream(os);
					Stream<Path> files = Files.walk(compileDir)) {
				// the manifest is written here rather than by the constructor
				// that takes one, which would stamp its entry with the time of
				// the build and make the jar a different file every time
				JarEntry manifestEntry = new JarEntry(JarFile.MANIFEST_NAME);
				manifestEntry.setTimeLocal(PACKAGED_AT);
				jos.putNextEntry(manifestEntry);
				manifest.write(jos);
				jos.closeEntry();
				List<Path> entries = files.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
				for (Path f : entries) {
					String name = compileDir.relativize(f).toString().replace('\\', '/');
					JarEntry entry = new JarEntry(name);
					// a fixed time rather than the file's: the build directory
					// says the jar is the one those inputs produce, and a jar
					// that carries the minute it was packed is a different file
					// every time it is packed. setTimeLocal so that the zip's
					// own field does not depend on the time zone either.
					entry.setTimeLocal(PACKAGED_AT);
					jos.putNextEntry(entry);
					try (InputStream is = Files.newInputStream(f)) {
						byte[] buf = new byte[65536];
						int n;
						while ((n = is.read(buf)) > 0) {
							jos.write(buf, 0, n);
						}
					}
					jos.closeEntry();
				}
			}
			try {
				Files.move(tmp, jar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				// not every filesystem can do it; the rename is still the last
				// step, so a reader sees either the old jar or the new one
				Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
			}
			// after the jar, not before: the two cannot be put in place as one,
			// and a jar without its fingerprint is built again, while a
			// fingerprint without its jar would be a promise about nothing
			Util.writeString(fingerprintFile(jar), Fingerprint.of(jar).toString());
		} finally {
			// a jar that was never finished must not be left behind in the
			// cache directory, where nothing would ever clean it up
			Util.deletePath(tmp, true);
		}
	}
}
