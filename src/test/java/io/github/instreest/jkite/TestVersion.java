package io.github.instreest.jkite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A class path holds a manifest per jar, so the version cannot be read out of
 * the first one found: it is whichever jar happens to come first.
 */
class TestVersion {

	@TempDir
	Path tempDir;

	/** Writes an empty jar whose manifest carries the given attributes. */
	private Path jar(String name, String... keysAndValues) throws IOException {
		Manifest manifest = new Manifest();
		Attributes attrs = manifest.getMainAttributes();
		attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
		for (int i = 0; i < keysAndValues.length; i += 2) {
			attrs.putValue(keysAndValues[i], keysAndValues[i + 1]);
		}
		Path jar = tempDir.resolve(name);
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
			// the manifest is all this jar is for
		}
		return jar;
	}

	/** Closeable: an open loader holds its jars open, and Windows notices. */
	private URLClassLoader classPath(Path... jars) throws IOException {
		URL[] urls = new URL[jars.length];
		for (int i = 0; i < jars.length; i++) {
			urls[i] = jars[i].toUri().toURL();
		}
		// no parent, so only these jars are searched
		return new URLClassLoader(urls, null);
	}

	@Test
	void theVersionIsReadFromOurOwnManifest() throws IOException {
		try (URLClassLoader cp = classPath(
				jar("other.jar", "Implementation-Title", "something else"),
				jar("jkite.jar", Version.ATTRIBUTE, "1.2.3"))) {
			assertEquals("1.2.3", Version.fromManifests(cp));
		}
	}

	@Test
	void aManifestOfOurOwnIsFoundWhereverItSits() throws IOException {
		try (URLClassLoader cp = classPath(
				jar("jkite.jar", Version.ATTRIBUTE, "1.2.3"),
				jar("other.jar", "Implementation-Title", "something else"))) {
			assertEquals("1.2.3", Version.fromManifests(cp));
		}
	}

	@Test
	void aClassPathWithoutOneSaysSo() throws IOException {
		try (URLClassLoader cp = classPath(jar("other.jar", "Implementation-Title", "something else"))) {
			assertEquals("unknown", Version.fromManifests(cp));
		}
	}

	@Test
	void anEmptyClassPathSaysSo() throws IOException {
		try (URLClassLoader cp = classPath()) {
			assertEquals("unknown", Version.fromManifests(cp));
		}
	}
}
