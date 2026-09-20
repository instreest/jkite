package io.github.instreest.jkite;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The one thing jkite does that cannot be undone.
 *
 * It asks before it downloads; it does not ask before it deletes, because
 * --clear-cache is somebody asking. What it owes instead is to say what it is
 * about to remove while there is still time to read it - which is also the
 * only place a JKITE_DIR or JKITE_CACHE_DIR pointing somewhere unintended
 * becomes visible.
 */
class TestClearCache {

	/** What was said, in order, and whether the files were still there. */
	private static final class Said {
		final List<String> lines = new ArrayList<>();
		final List<Boolean> jarStillThere = new ArrayList<>();
		private final Path jar;

		Said(Path jar) {
			this.jar = jar;
		}

		void accept(String line) {
			lines.add(line);
			jarStillThere.add(Files.exists(jar));
		}

		/** The index of the first line naming the directory. */
		int firstMentionOf(String text) {
			for (int i = 0; i < lines.size(); i++) {
				if (lines.get(i).contains(text)) {
					return i;
				}
			}
			return -1;
		}
	}

	@Test
	void itSaysWhatItIsAboutToRemoveBeforeItRemovesIt() throws IOException {
		Path jars = Settings.getCacheDir(Settings.CacheClass.jars);
		Path jar = Files.write(jars.resolve("clear-cache-test.jar"),
				"x".getBytes(StandardCharsets.UTF_8));
		Said said = new Said(jar);

		Settings.clearCache(said::accept);

		int named = said.firstMentionOf(jars.toString());
		assertTrue(named >= 0, "it never said which directory it was clearing: " + said.lines);
		assertTrue(said.jarStillThere.get(named),
				"the directory was named only after it had been emptied, which is too late to read:\n"
						+ String.join("\n", said.lines));
		assertFalse(Files.exists(jar), "it said it would remove the jar and did not: " + said.lines);
	}

	/**
	 * The JDKs are minutes each to fetch and are pinned, so they are kept -
	 * and the run says so rather than leaving the reader to wonder whether
	 * they went too.
	 */
	@Test
	void itSaysTheJdksAreKept() {
		List<String> lines = new ArrayList<>();

		Settings.clearCache(lines::add);

		assertTrue(lines.stream().anyMatch(l -> l.contains("kept the JDKs")),
				String.join("\n", lines));
	}

	/**
	 * The dependency jars go too, because that is what the option is for.
	 *
	 * They used to live in ~/.m2/repository, so --clear-cache removed the
	 * resolved class paths and left the files they named: the next run
	 * compiled against exactly the same bytes as the last one, and the option
	 * had not done the one thing its name promises. "Start from nothing"
	 * meant nothing at all while the jars that reach the class path were
	 * somewhere jkite did not empty.
	 */
	@Test
	void theDependencyJarsGoTooSoThatTheNextRunReallyStartsCold() throws IOException {
		Path deps = Settings.getCacheDir(Settings.CacheClass.deps);
		Path jar = Files.createDirectories(deps.resolve("com/example/thing/1.0"))
			.resolve("thing-1.0.jar");
		Files.write(jar, "x".getBytes(StandardCharsets.UTF_8));
		Said said = new Said(jar);

		Settings.clearCache(said::accept, null);

		int named = said.firstMentionOf(deps.toString());
		assertTrue(named >= 0, "it never said it would clear the dependencies: " + said.lines);
		assertTrue(said.jarStillThere.get(named),
				"it named the directory only after emptying it:\n" + String.join("\n", said.lines));
		assertFalse(Files.exists(jar), "the dependency jar is still there: " + said.lines);
	}

	/**
	 * A repository somebody else named is not jkite's to empty. Pointed at
	 * ~/.m2/repository - which is the reason the variable exists - clearing
	 * it would throw away every artifact every other build on the machine had
	 * fetched, from an option whose blast radius reads as "jkite's cache".
	 */
	@Test
	void aRepositorySomebodyElseNamedIsLeftAlone() throws IOException {
		Path deps = Settings.getCacheDir(Settings.CacheClass.deps);
		Path jar = Files.createDirectories(deps.resolve("com/example/kept/1.0"))
			.resolve("kept-1.0.jar");
		Files.write(jar, "x".getBytes(StandardCharsets.UTF_8));
		List<String> lines = new ArrayList<>();

		Settings.clearCache(lines::add, "/somewhere/of/their/own");

		assertTrue(lines.stream().anyMatch(l -> l.contains("not jkite's to empty")),
				String.join("\n", lines));
		assertTrue(Files.exists(jar), "it emptied the cache repository anyway: " + lines);
	}

	/**
	 * Every kind of cached thing sits under the cache directory, with no
	 * variable of its own to move it.
	 *
	 * There used to be JKITE_CACHE_DIR_JARS and two siblings, inherited and
	 * never written down anywhere. The launcher does not read them - it knows
	 * only JKITE_CACHE_DIR - so setting the JDKs one gave a machine two JDK
	 * caches and two downloads of the same JDK, one for each half of a run;
	 * and an undocumented variable decided which directory --clear-cache
	 * emptied. This is what makes both of those impossible.
	 */
	@Test
	void everyCacheKindIsUnderTheOneCacheDirectory() {
		Path cache = Settings.getCacheDir();

		for (Settings.CacheClass kind : Settings.CacheClass.values()) {
			Path dir = Settings.getCacheDir(kind);
			assertTrue(dir.startsWith(cache), kind + " is kept outside the cache directory: " + dir);
			assertTrue(dir.getFileName().toString().equals(kind.name()),
					kind + " is not in a directory of its own: " + dir);
		}
	}
}
