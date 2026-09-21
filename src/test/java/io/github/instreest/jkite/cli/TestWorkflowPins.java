package io.github.instreest.jkite.cli;

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
 * Every action a workflow runs is pinned to a commit, not to a tag.
 *
 * jkite's whole argument is that what runs on your machine is what the project
 * asked for, checked against a SHA-256 the project commits. A workflow that
 * says <code>actions/checkout@v7</code> is not that: v7 is a name upstream can
 * repoint at any time, so the code that builds and releases jkite could change
 * without a commit here. It would be an odd thing for this project of all
 * projects to leave to trust, and a release workflow is exactly where it would
 * matter - it has the token that can publish.
 *
 * The comment is not decoration. Dependabot reads <code>@&lt;sha&gt; #
 * v1.2.3</code>, and keeps both halves in step when it raises the update; drop
 * the comment and the pin still works but nobody can read what it is, and the
 * pull request that moves it stops saying what changed.
 *
 * This is here rather than in a review checklist because the one thing that
 * did go stale - four actions left on Node 20 until the runner started warning
 * on every run - went stale precisely because it lived in nobody's checklist.
 */
class TestWorkflowPins {

	/** owner/repo, optionally a path inside it, then a full commit and its tag. */
	private static final Pattern PINNED = Pattern.compile(
			"uses: [\\w.-]+/[\\w.-]+(?:/[\\w./-]+)?@[0-9a-f]{40} # v\\d+\\.\\d+\\.\\d+\\s*$");

	private static final Pattern USES = Pattern.compile("^\\s*(?:- )?uses: .*$");

	@Test
	void everyActionIsPinnedToACommitAndSaysWhichRelease() throws IOException {
		List<String> loose = new ArrayList<>();
		int checked = 0;
		for (Path workflow : workflows()) {
			List<String> lines = Files.readAllLines(workflow, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				if (!USES.matcher(line).matches()) {
					continue;
				}
				checked++;
				Matcher pinned = PINNED.matcher(line.trim());
				if (!pinned.find()) {
					loose.add(workflow + ":" + (i + 1) + "  " + line.trim());
				}
			}
		}

		assertTrue(checked >= 10,
				"the workflows no longer use actions, so this is checking nothing: " + checked);
		assertTrue(loose.isEmpty(),
				"a tag is a name upstream can repoint; pin the commit and say which release it"
						+ " is, as \"uses: owner/repo@<40 hex> # v1.2.3\":\n"
						+ String.join("\n", loose));
	}

	private static List<Path> workflows() throws IOException {
		List<Path> files = new ArrayList<>();
		try (Stream<Path> found = Files.list(Paths.get(".github/workflows"))) {
			found.filter(p -> p.getFileName().toString().endsWith(".yml")).forEach(files::add);
		}
		assertTrue(files.size() >= 2, "the workflows moved: " + files);
		return files;
	}
}
