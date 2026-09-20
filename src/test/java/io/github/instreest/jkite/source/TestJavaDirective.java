package io.github.instreest.jkite.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What <code>//JAVA</code> accepts. The parser is upstream's and is mirrored,
 * so this is not a rule jkite chose and not one it can quietly change; it is
 * written down here because the class that consumes the result understands
 * more forms than ever reach it, and a reader of that class would otherwise
 * conclude that a script can pin a full version.
 */
class TestJavaDirective {

	@TempDir
	Path dir;

	private Project projectWith(String firstLine) throws IOException {
		Path script = dir.resolve("Tool.java");
		Files.write(script, (firstLine + "\nclass Tool { public static void main(String... a) {} }\n")
			.getBytes(StandardCharsets.UTF_8));
		return new Project(script, Collections.emptyMap());
	}

	@Test
	void aMajorVersionIsAccepted() throws IOException {
		assertEquals("17", projectWith("//JAVA 17").getJavaVersion());
	}

	@Test
	void aMajorVersionAndLaterIsAccepted() throws IOException {
		assertEquals("17+", projectWith("//JAVA 17+").getJavaVersion());
	}

	@Test
	void noDirectiveLeavesTheVersionOpen() throws IOException {
		assertNull(projectWith("// nothing declared").getJavaVersion());
	}

	/**
	 * The form the docs used to recommend for a reproducible build. It is
	 * refused, so there is no such thing here, and saying so is the point of
	 * this test: RequestedVersion.parse would accept it, and nothing between
	 * the script and that method ever will.
	 */
	@Test
	void aFullVersionIsRefused() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> projectWith("//JAVA 25.0.3"));

		assertTrue(e.getMessage().contains("number optionally followed by a plus sign"), e.getMessage());
	}
}
