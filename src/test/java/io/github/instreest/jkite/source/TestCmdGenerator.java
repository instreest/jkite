package io.github.instreest.jkite.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jbang.ExitException;
import io.github.instreest.jkite.Settings;

/**
 * The java command line a built script is run with.
 *
 * Two things here are easy to get wrong and expensive when they are. An option
 * after the main class is not an option at all - it is an argument the script
 * receives - and an option the JVM does not recognise is not ignored: it
 * refuses to start. Which options it recognises depends on how old it is, and
 * that is the reason the command is built from a version rather than from a JDK.
 */
class TestCmdGenerator {

	/** A java new enough for everything the generator can add. */
	private static final int JAVA_22 = 22;
	private static final String JAVA = "/jdks/21/bin/java";

	@TempDir
	Path dir;

	private Project project(String... lines) throws IOException {
		return project(Collections.emptyMap(), lines);
	}

	/** A project of one script, with the given -D properties. */
	private Project project(Map<String, String> properties, String... lines) throws IOException {
		Path script = dir.resolve("Tool.java");
		StringBuilder source = new StringBuilder();
		for (String line : lines) {
			source.append(line).append('\n');
		}
		source.append("public class Tool { public static void main(String... a) {} }\n");
		Files.write(script, source.toString().getBytes(StandardCharsets.UTF_8));
		return new Project(script, properties);
	}

	private Path jar() {
		return dir.resolve("Tool.jar");
	}

	private List<String> cmd(Project project, int javaMajor) {
		return new CmdGenerator(project, jar()).generate(JAVA, javaMajor);
	}

	@Test
	void theJavaComesFirstAndTheMainClassAfterEveryOption() throws IOException {
		List<String> cmd = cmd(project("//MAIN Tool"), JAVA_22);

		assertEquals(JAVA, cmd.get(0));
		int main = cmd.indexOf("Tool");
		assertTrue(main > 0, cmd.toString());
		assertEquals(cmd.size() - 1, main, "nothing may follow the main class but the script's arguments: " + cmd);
	}

	@Test
	void theScriptsArgumentsFollowTheMainClassInOrder() throws IOException {
		List<String> cmd = new CmdGenerator(project("//MAIN Tool"), jar())
			.arguments(Arrays.asList("--since", "2026-01"))
			.generate(JAVA, JAVA_22);

		assertEquals(Arrays.asList("Tool", "--since", "2026-01"), cmd.subList(cmd.size() - 3, cmd.size()));
	}

	/**
	 * Both kinds of runtime option are the JVM's, so both go in front of the
	 * class path and the main class; the script's own come first because the
	 * command line is the later word.
	 */
	@Test
	void runtimeOptionsFromTheScriptComeBeforeTheOnesGivenOnTheCommandLine() throws IOException {
		List<String> cmd = new CmdGenerator(project("//RUNTIME_OPTIONS -Xmx128m", "//MAIN Tool"), jar())
			.runtimeOptions(Collections.singletonList("-Xss1m"))
			.generate(JAVA, JAVA_22);

		assertEquals(Arrays.asList(JAVA, "-Xmx128m", "-Xss1m"), cmd.subList(0, 3));
		assertTrue(cmd.indexOf("-Xss1m") < cmd.indexOf("-classpath"), cmd.toString());
	}

	@Test
	void addOpensAndAddExportsAreOpenedToTheUnnamedModule() throws IOException {
		List<String> cmd = cmd(project(
				"//MANIFEST Add-Opens=java.base/java.lang",
				"//MANIFEST Add-Exports=java.base/sun.nio.ch",
				"//MAIN Tool"), JAVA_22);

		assertTrue(cmd.contains("--add-opens=java.base/java.lang=ALL-UNNAMED"), cmd.toString());
		assertTrue(cmd.contains("--add-exports=java.base/sun.nio.ch=ALL-UNNAMED"), cmd.toString());
	}

	/** These manifest attributes hold a list, spelled the way a manifest spells one. */
	@Test
	void eachModuleInOneEntryBecomesAnOptionOfItsOwn() throws IOException {
		Project project = project("//MAIN Tool");
		project.getManifestAttributes().put(Project.ATTR_ADD_OPENS, "java.base/java.lang  java.base/java.util");

		List<String> cmd = cmd(project, JAVA_22);

		assertTrue(cmd.contains("--add-opens=java.base/java.lang=ALL-UNNAMED"), cmd.toString());
		assertTrue(cmd.contains("--add-opens=java.base/java.util=ALL-UNNAMED"), cmd.toString());
	}

	/** Modules arrived in Java 9; before that the option is not one. */
	@Test
	void addOpensIsLeftOutOnAJavaWithoutModules() throws IOException {
		List<String> cmd = cmd(project("//MANIFEST Add-Opens=java.base/java.lang", "//MAIN Tool"), 8);

		assertFalse(cmd.toString().contains("--add-opens"), cmd.toString());
	}

	/**
	 * --enable-native-access arrived in Java 22, and unlike the two above it
	 * takes the module on its own.
	 */
	@Test
	void enableNativeAccessIsAddedFromJava22() throws IOException {
		Project project = project("//MANIFEST Enable-Native-Access=ALL-UNNAMED", "//MAIN Tool");

		assertTrue(cmd(project, 22).contains("--enable-native-access=ALL-UNNAMED"), "22 should have it");
	}

	@Test
	void enableNativeAccessIsLeftOutBeforeJava22() throws IOException {
		List<String> cmd = cmd(project("//MANIFEST Enable-Native-Access=ALL-UNNAMED", "//MAIN Tool"), 21);

		assertFalse(cmd.toString().contains("--enable-native-access"), cmd.toString());
	}

	@Test
	void previewTurnsOnPreviewFeatures() throws IOException {
		assertTrue(cmd(project("//PREVIEW", "//MAIN Tool"), JAVA_22).contains("--enable-preview"));
		assertFalse(cmd(project("//MAIN Tool"), JAVA_22).contains("--enable-preview"));
	}

	@Test
	void thePropertiesGivenToTheRunArePassedOnToTheJvm() throws IOException {
		Map<String, String> properties = new LinkedHashMap<>();
		properties.put("env", "staging");

		List<String> cmd = cmd(project(properties, "//MAIN Tool"), JAVA_22);

		assertTrue(cmd.contains("-Denv=staging"), cmd.toString());
		assertTrue(cmd.indexOf("-Denv=staging") < cmd.indexOf("Tool"), "a -D after the main class is the script's: " + cmd);
	}

	@Test
	void theClassPathIsTheJarWhenTheScriptHasNoDependencies() throws IOException {
		List<String> cmd = cmd(project("//MAIN Tool"), JAVA_22);

		int cp = cmd.indexOf("-classpath");
		assertTrue(cp > 0, cmd.toString());
		assertEquals(jar().toAbsolutePath().toString(), cmd.get(cp + 1));
		assertFalse(cmd.get(cp + 1).contains(Settings.CP_SEPARATOR), "there is nothing to separate: " + cmd);
	}

	/** Without a main class there is nothing to run, and it says how to name one. */
	@Test
	void aScriptWithNoMainClassIsRefusedWithSomethingToDoAboutIt() throws IOException {
		Project project = project("class Other {}");
		project.setMainClass(null);

		ExitException e = assertThrows(ExitException.class, () -> cmd(project, JAVA_22));

		assertTrue(e.getMessage().contains("//MAIN"), e.getMessage());
	}
}
