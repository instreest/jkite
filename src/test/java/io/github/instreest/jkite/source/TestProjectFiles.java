package io.github.instreest.jkite.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import dev.jbang.ExitException;

/**
 * A //FILES target names a place inside the jar, so a target that leaves it
 * would name a place on the machine instead. Nothing legitimate needs that.
 */
class TestProjectFiles {

	@TempDir
	Path tempDir;

	private Project projectWith(String filesDirective) throws IOException {
		Path dir = Files.createDirectories(tempDir.resolve("tool"));
		Files.write(dir.resolve("data.txt"), "x".getBytes(StandardCharsets.UTF_8));
		Path script = dir.resolve("Tool.java");
		Files.write(script, (filesDirective + "\nclass Tool { public static void main(String... a) {} }\n")
			.getBytes(StandardCharsets.UTF_8));
		return new Project(script, Collections.emptyMap());
	}

	@Test
	void aTargetThatLeavesTheJarIsRefused() {
		ExitException e = assertThrows(ExitException.class,
				() -> projectWith("//FILES ../../../evil.txt=data.txt"));
		assertTrue(e.getMessage().contains("//FILES"), e.getMessage());
	}

	/**
	 * Windows does not call this absolute, because it names no drive, but it
	 * still starts at the root of whichever drive the run is on.
	 */
	@Test
	void aTargetFromTheRootIsRefused() {
		ExitException e = assertThrows(ExitException.class,
				() -> projectWith("//FILES /etc/evil.txt=data.txt"));
		assertTrue(e.getMessage().contains("//FILES"), e.getMessage());
	}

	/** Elsewhere "C:" is an ordinary directory name, so only Windows can tell. */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void aTargetOnAnotherDriveIsRefused() {
		assertThrows(ExitException.class, () -> projectWith("//FILES C:/evil.txt=data.txt"));
	}

	@Test
	void aTargetThatStaysInsideIsKept() throws IOException {
		Project p = projectWith("//FILES config/data.txt=data.txt");
		Path root = tempDir.resolve("out");

		assertEquals(1, p.getResources().size());
		assertEquals(root.resolve("config").resolve("data.txt"),
				p.getResources().get(0).to(root));
	}

	@Test
	void aTargetThatDoublesBackButStaysInsideIsKept() throws IOException {
		Project p = projectWith("//FILES a/../b/data.txt=data.txt");
		Path root = tempDir.resolve("out");

		assertEquals(root.resolve("b").resolve("data.txt"), p.getResources().get(0).to(root));
	}
}
