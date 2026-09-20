package io.github.instreest.jkite.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import dev.jbang.ExitException;

/**
 * Where folding a path and walking it give different answers.
 *
 * The shape that matters is a link followed by "..", because that is the one
 * where the two disagree about which file a path names - not which spelling
 * of it, which file. Everything else here is a guard on the ways this could
 * go wrong instead.
 */
class TestRealPath {

	@TempDir
	Path dir;

	private String read(Path at) throws IOException {
		return new String(Files.readAllBytes(at), StandardCharsets.UTF_8).trim();
	}

	private Path file(Path at, String contents) throws IOException {
		Files.createDirectories(at.getParent());
		Files.write(at, contents.getBytes(StandardCharsets.UTF_8));
		return at;
	}

	/**
	 * The whole point, in one test.
	 *
	 * real/link points at elsewhere, so "real/link/../which.txt" walks into
	 * elsewhere and then back out of it, landing at the top - while folding
	 * the string cancels "link" against ".." and stays inside real. Two
	 * different files, and each says which one it is, so this reads the file
	 * back rather than comparing path spellings and hoping.
	 *
	 * The third read is the anchor: handing the unfolded path straight to the
	 * file system is what a shell does, and it has to agree with the answer
	 * RealPath gives, or this test is measuring its own fixture.
	 */
	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void aLinkFollowedByDotDotNamesTheFileTheShellWouldOpen() throws Exception {
		file(dir.resolve("real/which.txt"), "folded");
		file(dir.resolve("which.txt"), "walked");
		Files.createDirectories(dir.resolve("elsewhere"));
		Files.createSymbolicLink(dir.resolve("real/link"), dir.resolve("elsewhere"));
		Path typed = dir.resolve("real/link/../which.txt");

		Path resolved = RealPath.of(typed, "The script");

		assertEquals("walked", read(resolved),
				"it folded the string and reached the other file: " + resolved);
		assertEquals(read(typed), read(resolved),
				"it does not agree with what the file system does with the path as typed");
		assertEquals("folded", read(typed.toAbsolutePath().normalize()),
				"the fixture no longer distinguishes the two, so this test proves nothing");
	}

	/** A path with no links in it is left where it was, so nothing else moves. */
	@Test
	void anOrdinaryPathIsUnchanged() throws Exception {
		Path made = file(dir.resolve("plain/Report.java"), "x");

		assertEquals(made.toRealPath(), RealPath.of(dir.resolve("plain/./Report.java"), "The script"));
	}

	/** Relative in, absolute out, as before. */
	@Test
	void aRelativePathIsStillMadeAbsolute() {
		assertTrue(RealPath.of(Path.of("."), "The script").isAbsolute());
	}

	/**
	 * A path that is not there is refused, not folded.
	 *
	 * toRealPath() needs the file to exist, and every path that comes through
	 * here is one jkite is about to read, so there is no case where carrying
	 * on with a guessed path is the right answer - the failure would only
	 * surface further on as something harder to read. The message names which
	 * file it was, because "could not be found" on its own is no use when a
	 * script pulls in siblings with //SOURCES and resources with //FILES.
	 */
	@Test
	void aPathThatIsNotThereIsRefusedAndSaysWhichItWas() {
		Path missing = dir.resolve("nope/Report.java");

		ExitException e = assertThrows(ExitException.class,
				() -> RealPath.of(missing, "The source named by //SOURCES"));

		assertEquals(ExitException.EXIT_INVALID_INPUT, e.getStatus(), e.getMessage());
		assertTrue(e.getMessage().contains("//SOURCES"), e.getMessage());
		assertTrue(e.getMessage().contains(missing.toString()), e.getMessage());
	}

	/**
	 * A ".." over a directory that is not there is the same answer, and it is
	 * worth its own test: this is exactly the case folding used to swallow -
	 * it would cancel "nope" against ".." and hand back a path that looks
	 * perfectly good and names a file nobody asked for.
	 */
	@Test
	void aDotDotOverAMissingDirectoryIsRefusedRatherThanCancelled() {
		Path missing = dir.resolve("nope/../Report.java");

		ExitException e = assertThrows(ExitException.class,
				() -> RealPath.of(missing, "The script"));

		assertTrue(e.getMessage().contains("could not be found"), e.getMessage());
	}
}
