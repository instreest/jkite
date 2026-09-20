package io.github.instreest.jkite.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The installer is documented as "curl ... | bash", and bash reads a pipe as
 * it goes: it runs each command as soon as it has read it, so a connection
 * that drops halfway runs the first half of the script. What that used to buy
 * was an exit code of 0, the word "Installing..." and an empty jkite/
 * directory - an installation that said nothing was wrong and had not
 * happened.
 *
 * The whole body is one function now, called on the last line, so a truncated
 * copy defines something and never calls it. This checks that property
 * directly rather than by cutting the script at a few places and hoping those
 * were the interesting ones: no prefix of the file may both parse and contain
 * the call.
 */
@DisabledOnOs(OS.WINDOWS)
class TestInstallerTruncation extends AbstractScriptTest {

	private static final Path INSTALLER = Paths.get("dist/install.sh").toAbsolutePath();
	private static final String CALL = "jkite_install \"$@\"";

	@Test
	void noHalfOfTheInstallerCanRunAnything() throws Exception {
		requireBash();
		List<String> lines = Files.readAllLines(INSTALLER, StandardCharsets.UTF_8);
		assertTrue(lines.stream().anyMatch(l -> l.trim().equals(CALL)),
				"the installer no longer ends in a single call, so this test is checking nothing");

		List<Integer> runnable = new ArrayList<>();
		for (int cut = 1; cut < lines.size(); cut++) {
			String prefix = String.join("\n", lines.subList(0, cut)) + "\n";
			if (prefix.contains(CALL) && parses(prefix)) {
				runnable.add(cut);
			}
		}

		assertTrue(runnable.isEmpty(),
				"a connection dropping after these line numbers would leave a runnable half of "
						+ "the installer: " + runnable);
	}

	/** What bash itself says about a prefix, rather than what we think of it. */
	private boolean parses(String script) throws IOException, InterruptedException {
		Path file = Files.write(tempDir.resolve("prefix.sh"), script.getBytes(StandardCharsets.UTF_8));
		Process p = new ProcessBuilder("bash", "-n", file.toString())
			.redirectErrorStream(true)
			.start();
		assertTrue(p.waitFor(30, TimeUnit.SECONDS), "bash -n did not finish");
		return p.exitValue() == 0;
	}
}
