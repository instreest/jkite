package io.github.instreest.jkite.dependencies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jbang.ExitException;
import dev.jbang.dependencies.MavenRepo;
import dev.jbang.util.Util;

/**
 * Resolving <code>//DEPS</code>, which is the one download a project makes on
 * every run and the one that becomes code the JVM loads. It is exercised here
 * against a Maven repository written into a temporary directory and named with
 * a <code>file:</code> URL, so the whole path runs - coordinates, descriptors,
 * transitive dependencies, BOMs and the on-disk cache - without a network and
 * without depending on what Maven Central happens to hold today.
 *
 * Two things are deliberately not covered here. The resolved-dependency cache
 * cannot be: DependencyCache holds it in a static map for the life of the JVM,
 * so a second call in the same process is answered from memory whatever the
 * file on disk says, and nothing an in-process test can do tells the two
 * apart. And {@code DependencyResolver.resolveArtifact}, the single-artifact
 * entry point
 * the JVM index uses, takes no repositories and so always asks Maven Central,
 * which is the one thing these tests are built to avoid.
 *
 * The local repository the artifacts are copied into is the test run's own:
 * build.gradle points JKITE_MAVEN_REPO at a directory under build/. A
 * ~/.m2/settings.xml that mirrors every repository would still redirect these
 * lookups; CI has none.
 */
class TestDependencyResolution {

	@TempDir
	Path dir;

	private Path repo;
	private List<MavenRepo> repos;

	@BeforeEach
	void makeRepository() throws IOException {
		repo = Files.createDirectories(dir.resolve("repository"));
		repos = Collections.singletonList(new MavenRepo("test", repo.toUri().toString()));
	}

	@Test
	void resolvesACoordinateToTheJarItNames() throws IOException {
		publish("com.example", "one", "1.0");

		List<ArtifactInfo> resolved = DependencyResolver.resolve(deps("com.example:one:1.0"), repos);

		assertEquals(1, resolved.size(), resolved.toString());
		ArtifactInfo only = resolved.get(0);
		assertEquals("one", only.getCoordinate().getArtifactId());
		assertEquals("1.0", only.getCoordinate().getVersion());
		assertTrue(Files.isRegularFile(only.getFile()), "the jar was not fetched: " + only.getFile());
	}

	/**
	 * What a script declares is rarely all it needs. If the dependencies of a
	 * dependency were left out, the class path would be short of exactly the
	 * classes the script never had to name.
	 */
	@Test
	void bringsTheDependenciesOfADependency() throws IOException {
		publish("com.example", "leaf", "1.0");
		publish("com.example", "middle", "1.0", dependsOn("com.example", "leaf", "1.0"));
		publish("com.example", "top", "1.0", dependsOn("com.example", "middle", "1.0"));

		List<ArtifactInfo> resolved = DependencyResolver.resolve(deps("com.example:top:1.0"), repos);

		assertEquals(Arrays.asList("top", "middle", "leaf"), artifactIds(resolved),
				"the class path is not the declared artifact followed by what it needs");
	}

	/**
	 * A BOM (<code>@pom</code>) is declared so that the versions can be left out
	 * of the dependencies themselves. If it were resolved but not applied, the
	 * version would be empty and nothing would resolve.
	 */
	@Test
	void aBomSuppliesTheVersionADependencyLeavesOut() throws IOException {
		publish("com.example", "managed", "2.0");
		publishPom("com.example", "platform", "1.0",
				"<dependencyManagement><dependencies>"
						+ "<dependency><groupId>com.example</groupId><artifactId>managed</artifactId>"
						+ "<version>2.0</version></dependency>"
						+ "</dependencies></dependencyManagement>");

		List<ArtifactInfo> resolved = DependencyResolver.resolve(
				deps("com.example:platform:1.0@pom", "com.example:managed"), repos);

		assertEquals(Collections.singletonList("managed"), artifactIds(resolved), resolved.toString());
		assertEquals("2.0", resolved.get(0).getCoordinate().getVersion());
	}

	@Test
	void theSameDependencyNamedTwiceIsResolvedOnce() throws IOException {
		publish("com.example", "one", "1.0");

		List<ArtifactInfo> resolved = DependencyResolver.resolve(
				deps("com.example:one:1.0", "com.example:one:1.0"), repos);

		assertEquals(Collections.singletonList("one"), artifactIds(resolved), resolved.toString());
	}

	@Test
	void nothingDeclaredResolvesToNothing() {
		assertEquals(Collections.emptyList(), DependencyResolver.resolve(deps(), repos));
	}

	/**
	 * Maven is forgiving about a descriptor it cannot read; jkite is not,
	 * because a dependency that quietly does not arrive shows up as a compile
	 * error about a class the script does name.
	 */
	@Test
	void aDependencyThatIsNotThereStopsTheRun() {
		ExitException e = assertThrows(ExitException.class,
				() -> DependencyResolver.resolve(deps("com.example:absent:1.0"), repos));

		assertTrue(e.getMessage().contains("absent"), e.getMessage());
	}

	@Test
	void aVersionThatIsNotPublishedStopsTheRun() throws IOException {
		publish("com.example", "one", "1.0");

		ExitException e = assertThrows(ExitException.class,
				() -> DependencyResolver.resolve(deps("com.example:one:9.9"), repos));

		assertTrue(e.getMessage().contains("9.9"), e.getMessage());
	}

	/**
	 * Maven's own policy is to shrug at a descriptor it cannot read and carry on
	 * with a stub. jkite asks it not to, for what the script itself declares:
	 * the jar here is published and its pom is not, which is the one case the
	 * two policies answer differently, and forgiving would resolve the jar and
	 * say nothing.
	 *
	 * It is the declared dependencies this covers, not what they in turn pull
	 * in - the strict session is used to read their descriptors, while the
	 * resolution that follows runs on the ordinary one.
	 */
	@Test
	void aDeclaredDependencyWithNoDescriptorStopsTheRun() throws IOException {
		publishJarOnly("com.example", "nodescriptor", "1.0");

		ExitException e = assertThrows(ExitException.class,
				() -> DependencyResolver.resolve(deps("com.example:nodescriptor:1.0"), repos));

		assertTrue(e.getMessage().contains("nodescriptor"), e.getMessage());
	}

	// -------------------------------------------------------------------------
	// a Maven repository, written by hand
	// -------------------------------------------------------------------------

	private static List<String> deps(String... coordinates) {
		return Arrays.asList(coordinates);
	}

	private static List<String> artifactIds(List<ArtifactInfo> artifacts) {
		return artifacts.stream().map(a -> a.getCoordinate().getArtifactId()).collect(Collectors.toList());
	}

	private static String dependsOn(String group, String artifact, String version) {
		return "<dependencies><dependency>"
				+ "<groupId>" + group + "</groupId>"
				+ "<artifactId>" + artifact + "</artifactId>"
				+ "<version>" + version + "</version>"
				+ "</dependency></dependencies>";
	}

	private void publish(String group, String artifact, String version) throws IOException {
		publish(group, artifact, version, "");
	}

	/** A jar and the descriptor that goes with it, where Maven looks for them. */
	private void publish(String group, String artifact, String version, String body) throws IOException {
		Path base = artifactDir(group, artifact, version);
		writePom(base, group, artifact, version, "jar", body);
		writeJar(base.resolve(artifact + "-" + version + ".jar"), group, artifact);
	}

	/** A real zip: the resolver hands the file on to be put on a class path. */
	private static void writeJar(Path file, String group, String artifact) throws IOException {
		try (ZipOutputStream jar = new ZipOutputStream(Files.newOutputStream(file))) {
			jar.putNextEntry(new ZipEntry(group.replace('.', '/') + "/" + artifact + "/Marker.class"));
			jar.write(new byte[] { (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE });
			jar.closeEntry();
		}
	}

	/** A jar with no descriptor beside it, which Maven has a policy about. */
	private void publishJarOnly(String group, String artifact, String version) throws IOException {
		Path base = artifactDir(group, artifact, version);
		writeJar(base.resolve(artifact + "-" + version + ".jar"), group, artifact);
	}

	/** A BOM: a descriptor with no jar beside it. */
	private void publishPom(String group, String artifact, String version, String body) throws IOException {
		writePom(artifactDir(group, artifact, version), group, artifact, version, "pom", body);
	}

	private Path artifactDir(String group, String artifact, String version) throws IOException {
		return Files.createDirectories(
				repo.resolve(group.replace('.', '/')).resolve(artifact).resolve(version));
	}

	private void writePom(Path base, String group, String artifact, String version, String packaging, String body)
			throws IOException {
		String pom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
				+ "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
				+ "  <modelVersion>4.0.0</modelVersion>\n"
				+ "  <groupId>" + group + "</groupId>\n"
				+ "  <artifactId>" + artifact + "</artifactId>\n"
				+ "  <version>" + version + "</version>\n"
				+ "  <packaging>" + packaging + "</packaging>\n"
				+ "  " + body + "\n"
				+ "</project>\n";
		try (OutputStream out = Files.newOutputStream(base.resolve(artifact + "-" + version + ".pom"))) {
			out.write(pom.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
			for (Path p : paths.sorted(Collections.reverseOrder()).collect(Collectors.toList())) {
				Files.deleteIfExists(p);
			}
		}
	}

	/**
	 * What --offline says when the answer is not already on the machine.
	 *
	 * The resolver's own words are "Could not read artifact descriptor for
	 * ...", which mentions neither the network nor the option that turned it
	 * off; the reason sits three causes down where nobody looks. Somebody who
	 * passed --offline asked for exactly this outcome and should be told so in
	 * the first line, with the coordinates to fetch first.
	 */
	@Test
	void offlineSaysThatOfflineIsWhy() throws IOException {
		publish("com.example", "absent", "1.0");
		// published to a repository that is reachable, so the only reason this
		// cannot resolve is the switch - aether treats a file: repository as
		// remote like any other and will not look at it offline
		Util.setOffline(true);
		try {
			ExitException e = assertThrows(ExitException.class,
					() -> DependencyResolver.resolve(deps("com.example:absent:1.0"), repos));

			assertTrue(e.getMessage().contains("--offline"), e.getMessage());
			assertTrue(e.getMessage().contains("com.example:absent:1.0"), e.getMessage());
			assertTrue(!e.getMessage().contains("artifact descriptor"),
					"it is still the resolver's message, which does not mention offline: "
							+ e.getMessage());
		} finally {
			Util.setOffline(false);
		}
	}

	/** With the network on, the resolver's own message is the useful one. */
	@Test
	void withTheNetworkOnTheResolverStillSpeaksForItself() {
		ExitException e = assertThrows(ExitException.class,
				() -> DependencyResolver.resolve(deps("com.example:never-published:1.0"), repos));

		assertTrue(!e.getMessage().contains("--offline"),
				"it blamed --offline for a failure that had nothing to do with it: " + e.getMessage());
	}
}
