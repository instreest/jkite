package io.github.instreest.jkite.dependencies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jbang.dependencies.MavenCoordinate;

/**
 * The dependency cache holds the entries of every script on this machine, and
 * runs that share it are not aware of each other. What one of them writes must
 * therefore keep what the others wrote, and what it reads must be a whole class
 * path or nothing at all.
 */
class TestDependencyCache {

	/** The SHA-256 of the nine bytes "something", for the hand-written files. */
	private static final String DIGEST = "3fc9b689459d738f8c88a3a48aa9e33542016b7a4052e001aaa536fca74813cb";

	@TempDir
	Path dir;

	private Path file() {
		return dir.resolve("dependency_cache.txt");
	}

	/**
	 * The file a coordinate's jar is written to. Not the coordinate itself:
	 * Windows has no ":" in a file name, and a repository does not use one
	 * either.
	 */
	private Path jarOf(String coord) {
		return dir.resolve(coord.replace(':', '-') + ".jar");
	}

	/** Artifacts of real files, because a fingerprint is taken of one. */
	private List<ArtifactInfo> artifacts(String... coords) {
		return Arrays.stream(coords)
			.map(c -> {
				Path jar = jarOf(c);
				try {
					Files.write(jar, c.getBytes(StandardCharsets.UTF_8));
				} catch (IOException e) {
					throw new java.io.UncheckedIOException(e);
				}
				return new ArtifactInfo(MavenCoordinate.fromString(c), jar);
			})
			.collect(java.util.stream.Collectors.toList());
	}

	/**
	 * The entry another run stored while this one was resolving is in the file
	 * but not in what this one read at startup. Writing back what it read would
	 * drop it, and that run would resolve everything again on its next start.
	 */
	@Test
	void storingAnEntryKeepsTheOnesThisRunNeverSaw() {
		DependencyCache.merge(file(), "first", artifacts("com.example:one:1.0"));

		// a second run, which never saw the entry above
		DependencyCache.merge(file(), "second", artifacts("com.example:two:1.0"));

		Map<String, List<ArtifactInfo>> stored = DependencyCache.read(file());
		assertEquals(Arrays.asList("first", "second"), new java.util.ArrayList<>(stored.keySet()));
	}

	@Test
	void anEntryIsReadBackAsItWasWritten() {
		DependencyCache.merge(file(), "key", artifacts("com.example:one:1.0", "com.example:two:2.0"));

		List<ArtifactInfo> read = DependencyCache.read(file()).get("key");
		assertEquals(2, read.size());
		// the coordinate comes back naming the type it was given by default
		assertEquals("com.example:one:1.0@jar", read.get(0).getCoordinate().toMavenString());
		assertEquals(jarOf("com.example:two:2.0"), read.get(1).getFile());
		assertTrue(read.get(1).isUpToDate(), "the file is untouched, so the entry still stands");
	}

	/**
	 * A class path with one artifact missing still looks usable: every file it
	 * does name is there, so it would be handed out and the script would fail
	 * much later, on a class that cannot be found.
	 */
	@Test
	void aDamagedLineDropsItsWholeEntry() throws IOException {
		Files.write(file(), String.join("\n",
				"[good]",
				"com.example:one:1.0\t/does/not/matter/one.jar\t9:" + DIGEST,
				"",
				"[damaged]",
				"com.example:two:1.0\t/does/not/matter/two.jar\t9:" + DIGEST,
				"this line is not an artifact",
				"").getBytes(StandardCharsets.UTF_8));

		Map<String, List<ArtifactInfo>> read = DependencyCache.read(file());

		assertTrue(read.containsKey("good"));
		assertFalse(read.containsKey("damaged"), "an entry that cannot be read whole is not an entry");
	}

	/**
	 * What an older version wrote in the third field was a modification time.
	 * It is not a fingerprint, so the entry is resolved once more rather than
	 * being trusted on a field nobody can check.
	 */
	@Test
	void anEntryFromTheTimestampFormatIsDropped() throws IOException {
		Files.write(file(), String.join("\n",
				"[from an older jkite]",
				"com.example:two:1.0\t/does/not/matter/two.jar\t1789374264623",
				"").getBytes(StandardCharsets.UTF_8));

		assertEquals(Collections.emptySet(), DependencyCache.read(file()).keySet());
	}

	/** An entry that could never be checked again is not worth writing down. */
	@Test
	void anArtifactThatCouldNotBeReadIsNotCached() {
		List<ArtifactInfo> unreadable = Collections.singletonList(
				new ArtifactInfo(MavenCoordinate.fromString("com.example:gone:1.0"),
						dir.resolve("never-written.jar")));

		DependencyCache.merge(file(), "key", unreadable);

		assertEquals(Collections.emptySet(), DependencyCache.read(file()).keySet());
	}

	@Test
	void aMissingFileIsAnEmptyCache() {
		assertEquals(Collections.emptySet(), DependencyCache.read(file()).keySet());
	}
}
