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
 * A batch mistake that only Windows can report, caught here instead.
 *
 * cmd.exe parses %~ as batch-parameter substitution everywhere, comments
 * included - a "rem" line is read and acted on like any other. A valid one
 * ends in the argument it refers to, as %~dp0 does. One that does not, which
 * is what "%~s" in a sentence about 8.3 names is, stops the script with
 *
 *   The following usage of the path operator in batch-parameter
 *   substitution is invalid: %~s only
 *
 * and everything after it in that run is lost. That is what happened: a
 * comment explaining the short-path fix took out fourteen Windows tests,
 * and the only machine that could say so was a CI runner four minutes away.
 * The cost is not the mistake, it is the round trip - so it is checked here,
 * where the answer takes no time and does not need Windows.
 */
class TestBatchSyntax {

	/**
	 * A single %~ is a batch parameter and has to end in the argument it
	 * refers to - a digit, or the $VAR:digit form. A doubled one, %%~, is a
	 * for-variable and ends in that variable's letter, which is why "%%~sI"
	 * is right and "%~s" is not. Only the single form is looked at.
	 */
	private static final Pattern VALID = Pattern.compile("%~[fdpnxsatz]*(\\d|\\$[A-Za-z_]+:\\d)");
	private static final Pattern ANY = Pattern.compile("(?<!%)%~");

	@Test
	void everyBatchParameterSubstitutionNamesWhatItSubstitutes() throws IOException {
		List<String> bad = new ArrayList<>();
		for (Path file : batchFiles()) {
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++) {
				for (String found : suspect(lines.get(i))) {
					bad.add(file + ":" + (i + 1) + "  " + found + "   in: " + lines.get(i).trim());
				}
			}
		}

		assertTrue(bad.isEmpty(),
				"cmd.exe reads these as batch-parameter substitution and stops the script, even "
						+ "in a comment:\n" + String.join("\n", bad));
	}

	/**
	 * A variable that changes on every read is useless read with %%, because
	 * cmd.exe expands a whole parenthesised block once, when it parses it.
	 * So inside a "for" or an "if", %RANDOM% is one number repeated, not a
	 * new one each time round - which turns a retry loop into the same
	 * attempt twenty times. !RANDOM! is read each time, which is the point of
	 * delayed expansion.
	 *
	 * Written after making exactly that mistake in install.cmd's retry for
	 * its staging directory, where it would have looked like it worked: the
	 * first attempt succeeds almost always, and the retry only matters on the
	 * collision it exists for.
	 *
	 * Only %RANDOM% and %ERRORLEVEL% - the two that change under the script's
	 * feet. An ordinary variable set outside the block is meant to be read
	 * with %% and usually is.
	 */
	@Test
	void aVariableThatChangesIsNotReadWithParseTimeExpansion() throws IOException {
		Pattern volatileVar = Pattern.compile("%(RANDOM|ERRORLEVEL)%", Pattern.CASE_INSENSITIVE);
		List<String> bad = new ArrayList<>();
		for (Path file : batchFiles()) {
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			int depth = 0;
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				String code = line.trim().toLowerCase().startsWith("rem ") ? "" : line;
				if (depth > 0 && volatileVar.matcher(code).find()) {
					bad.add(file + ":" + (i + 1) + "  " + line.trim());
				}
				depth += count(code, '(') - count(code, ')');
				if (depth < 0) {
					depth = 0;
				}
			}
		}

		assertTrue(bad.isEmpty(),
				"cmd.exe expands a block once when it parses it, so these read one value and "
						+ "reuse it; write !RANDOM! or !ERRORLEVEL! instead:\n"
						+ String.join("\n", bad));
	}

	private static int count(String line, char c) {
		int n = 0;
		for (int i = 0; i < line.length(); i++) {
			if (line.charAt(i) == c) {
				n++;
			}
		}
		return n;
	}

	/** Every %~ on the line that the valid pattern does not account for. */
	private static List<String> suspect(String line) {
		List<String> out = new ArrayList<>();
		Matcher all = ANY.matcher(line);
		Matcher valid = VALID.matcher(line);
		while (all.find()) {
			if (!(valid.find(all.start()) && valid.start() == all.start())) {
				out.add(line.substring(all.start(), Math.min(line.length(), all.start() + 8)));
			}
		}
		return out;
	}

	/** Both copies: dist/ is what a project actually runs. */
	private static List<Path> batchFiles() throws IOException {
		List<Path> files = new ArrayList<>();
		for (String dir : new String[] { "src/main/scripts", "dist" }) {
			try (Stream<Path> found = Files.list(Paths.get(dir))) {
				found.filter(p -> p.getFileName().toString().endsWith(".cmd")).forEach(files::add);
			}
		}
		assertTrue(files.size() >= 7, "the batch files moved; this is checking nothing: " + files);
		return files;
	}
}
