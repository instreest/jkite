package io.github.instreest.jkite.jdk;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;

import dev.jbang.util.Util;

/**
 * What a JDK in the cache was installed from.
 *
 * The archive a JDK is unpacked from is checked against the SHA-256 published
 * next to it and then deleted, and until this was written down that was the end
 * of it: a directory under <code>jdks/</code> held a JDK with nothing to say
 * where it came from or what was checked. Re-reading a JDK to verify it is not
 * affordable - a few hundred megabytes over twenty thousand files, on every run
 * - so this does not verify anything. It records, so that the question "where
 * did this JDK come from" has an answer that does not depend on remembering.
 *
 * It sits inside the directory it describes, so that removing the JDK removes
 * it too, and it is written into the unpacked tree before that tree is moved
 * into place, so a JDK never appears without it.
 */
final class InstallRecord {
	/** The file this is kept in, inside the JDK's own directory. */
	static final String FILE_NAME = ".jkite-install";

	private static final String DISTRIBUTION = "distribution";
	private static final String VERSION = "version";
	private static final String URL = "url";
	private static final String SHA256 = "sha256";
	private static final String INSTALLED = "installed";

	private final Properties values;

	private InstallRecord(Properties values) {
		this.values = values;
	}

	/** Writes what was downloaded and verified into the JDK's directory. */
	static void write(Path jdkHome, JdkIndex.Entry entry, String verifiedSha256) throws IOException {
		Properties values = new Properties();
		values.setProperty(DISTRIBUTION, entry.distro);
		values.setProperty(VERSION, entry.version);
		values.setProperty(URL, entry.url);
		values.setProperty(SHA256, verifiedSha256);
		values.setProperty(INSTALLED, Instant.now().toString());
		try (OutputStream out = Files.newOutputStream(jdkHome.resolve(FILE_NAME))) {
			values.store(out, "Written by jkite. The archive below was downloaded and its SHA-256 checked"
					+ " against the one published next to it before this directory was filled.");
		}
	}

	/** What was recorded for the JDK in this directory, if anything was. */
	static Optional<InstallRecord> read(Path jdkHome) {
		Path file = jdkHome.resolve(FILE_NAME);
		if (!Files.isReadable(file)) {
			return Optional.empty();
		}
		Properties values = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			values.load(in);
		} catch (IOException | IllegalArgumentException e) {
			Util.verboseMsg("Could not read " + file + ": " + e);
			return Optional.empty();
		}
		return values.getProperty(URL) != null ? Optional.of(new InstallRecord(values)) : Optional.empty();
	}

	String url() {
		return values.getProperty(URL, "");
	}

	String sha256() {
		return values.getProperty(SHA256, "");
	}

	String installed() {
		return values.getProperty(INSTALLED, "");
	}

	/** One line saying where this JDK came from. */
	String describe() {
		return values.getProperty(DISTRIBUTION, "?") + " " + values.getProperty(VERSION, "?")
				+ " from " + url() + ", sha256 " + sha256() + ", installed " + installed();
	}
}
