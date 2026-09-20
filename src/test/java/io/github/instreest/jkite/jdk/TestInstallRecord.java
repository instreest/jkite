package io.github.instreest.jkite.jdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An installed JDK is a directory of files with nothing in it that says where
 * it came from. What was downloaded and what was checked against it is written
 * down beside it, so the question can still be answered afterwards.
 */
class TestInstallRecord {

	@TempDir
	Path dir;

	private static final String SHA = "3fc9b689459d738f8c88a3a48aa9e33542016b7a4052e001aaa536fca74813cb";

	private static JdkIndex.Entry entry() {
		return new JdkIndex.Entry("temurin", "25.0.4.1+1", "tgz",
				"https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25/OpenJDK25U.tar.gz");
	}

	@Test
	void whatWasInstalledIsReadBack() throws IOException {
		InstallRecord.write(dir, entry(), SHA);

		Optional<InstallRecord> read = InstallRecord.read(dir);

		assertTrue(read.isPresent());
		assertEquals(entry().url, read.get().url());
		assertEquals(SHA, read.get().sha256());
		assertFalse(read.get().installed().isEmpty(), "the time it was installed is part of it");
	}

	@Test
	void theDescriptionNamesTheArchiveAndItsDigest() throws IOException {
		InstallRecord.write(dir, entry(), SHA);

		String described = InstallRecord.read(dir).orElseThrow(AssertionError::new).describe();

		assertTrue(described.contains("temurin 25.0.4.1+1"), described);
		assertTrue(described.contains(entry().url), described);
		assertTrue(described.contains(SHA), described);
	}

	/** A JDK installed before this was written down, or by something else. */
	@Test
	void aDirectoryWithoutOneSaysSo() {
		assertFalse(InstallRecord.read(dir).isPresent());
	}

	@Test
	void aFileThatIsNotOneSaysSoToo() throws IOException {
		Files.write(dir.resolve(InstallRecord.FILE_NAME), "nothing to see".getBytes(StandardCharsets.UTF_8));

		assertFalse(InstallRecord.read(dir).isPresent());
	}
}
