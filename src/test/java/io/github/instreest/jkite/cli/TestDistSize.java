package io.github.instreest.jkite.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * What a project takes on by committing jkite/, said the same way everywhere.
 *
 * It is the number that decides whether someone is willing to commit this at
 * all, and it was stated in four places with three different values, none of
 * them right: README said 90 kB, dist/README.md 90, dist/install.sh and
 * misc/update-dist.sh 80, and it was 96. dist/README.md was left out of this
 * check when it was first written and promptly drifted again, which is the
 * argument for the check rather than against it. Prose drifts because nothing reads it; this reads it.
 *
 * The tolerance is wide on purpose. The point is not the exact figure - it is
 * that the three agree with each other and that none of them has quietly gone
 * stale by half.
 */
class TestDistSize {

	private static final Pattern STATED = Pattern.compile("about (\\d+) kB");

	private static final List<Path> SAYS_SO = java.util.Arrays.asList(
			Paths.get("README.md"),
			// the one a project's own contributors read, and the one that had
			// drifted furthest: it said 90 kB while README said 95
			Paths.get("dist/README.md"),
			// the fifth copy, and the one that had gone stalest: the diagram
			// said 89 kB while everything else said 98
			Paths.get("docs/ARCHITECTURE.md"),
			Paths.get("dist/install.sh"),
			Paths.get("misc/update-dist.sh"));

	@Test
	void everyPlaceThatNamesTheSizeIsStillRight() throws IOException {
		long actual = distBytes();
		List<String> wrong = new ArrayList<>();
		int found = 0;
		for (Path file : SAYS_SO) {
			Matcher m = STATED.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
			while (m.find()) {
				found++;
				long stated = Long.parseLong(m.group(1)) * 1000;
				if (Math.abs(stated - actual) > actual / 5) {
					wrong.add(file + " says " + m.group() + ", dist/ is " + actual / 1000 + " kB");
				}
			}
		}

		assertEquals(SAYS_SO.size(), found,
				"the size is no longer stated in each of " + SAYS_SO + "; this is checking nothing");
		assertTrue(wrong.isEmpty(), String.join("\n", wrong));
	}

	private static long distBytes() throws IOException {
		try (Stream<Path> files = Files.list(Paths.get("dist"))) {
			return files.filter(Files::isRegularFile).mapToLong(p -> {
				try {
					return Files.size(p);
				} catch (IOException e) {
					throw new java.io.UncheckedIOException(e);
				}
			}).sum();
		}
	}
}
