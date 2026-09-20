package io.github.instreest.jkite.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A cache entry is worth keeping while the file it names is still the file it
 * was taken of. These are the two ways a modification time answers that wrong.
 */
class TestFingerprint {

	@TempDir
	Path dir;

	private Path fileWith(String content) throws IOException {
		Path file = dir.resolve("artifact.jar");
		Files.write(file, content.getBytes(StandardCharsets.UTF_8));
		return file;
	}

	/**
	 * Restoring ~/.m2 from a CI cache, re-installing an unchanged module or
	 * copying a tree all rewrite the time and change nothing else. None of them
	 * is a reason to resolve again.
	 */
	@Test
	void aNewTimeOnTheSameBytesStillMatches() throws IOException {
		Path file = fileWith("the same bytes");
		Fingerprint fingerprint = Fingerprint.of(file);

		Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 60_000));

		assertTrue(fingerprint.matches(file));
	}

	/** And the other way round: the time says nothing about the content. */
	@Test
	void theSameTimeOnOtherBytesDoesNotMatch() throws IOException {
		Path file = fileWith("the first bytes");
		Fingerprint fingerprint = Fingerprint.of(file);
		FileTime when = Files.getLastModifiedTime(file);

		Files.write(file, "the second one".getBytes(StandardCharsets.UTF_8));
		Files.setLastModifiedTime(file, when);

		assertFalse(fingerprint.matches(file));
	}

	@Test
	void aChangeOfLengthDoesNotMatch() throws IOException {
		Path file = fileWith("short");
		Fingerprint fingerprint = Fingerprint.of(file);

		Files.write(file, "rather longer than before".getBytes(StandardCharsets.UTF_8));

		assertFalse(fingerprint.matches(file));
	}

	@Test
	void aFileThatIsGoneDoesNotMatch() throws IOException {
		Path file = fileWith("here for now");
		Fingerprint fingerprint = Fingerprint.of(file);

		Files.delete(file);

		assertFalse(fingerprint.matches(file));
	}

	@Test
	void aFingerprintSurvivesBeingWrittenDown() throws IOException {
		Fingerprint fingerprint = Fingerprint.of(fileWith("something"));

		Fingerprint read = Fingerprint.parse(fingerprint.toString());

		assertEquals(fingerprint, read);
		assertEquals(9L, read.size());
		assertTrue(read.matches(dir.resolve("artifact.jar")));
	}

	/** What an older version wrote there was a modification time. */
	@Test
	void aTimestampIsNotAFingerprint() {
		assertNull(Fingerprint.parse("1789374264623"));
		assertNull(Fingerprint.parse(""));
		assertNull(Fingerprint.parse(null));
		assertNull(Fingerprint.parse("12:not-a-digest"));
	}
}
