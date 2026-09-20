package io.github.instreest.jkite.spi;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a {@link DirectiveParser} has to deliver, expressed on the interface
 * alone.
 *
 * This is the contract jkite depends on, so a second implementation - one
 * on {@code dev.jbang:jbang.bin}, say - is held to this same test by extending
 * it and returning its own parser from {@link #parser()}. What the directives
 * themselves mean is tested against JBang's own test, which is mirrored in
 * {@code dev.jbang.source.parser.TestDirectives}; this is about the mapping to
 * the interface.
 */
class TestDirectiveParser {

	protected DirectiveParser parser() {
		return Providers.directiveParser();
	}

	private SourceDirectives parse(String content) {
		return parser().parse(content, Function.identity());
	}

	@Test
	void readsTheDirectivesOfASource() {
		SourceDirectives d = parse(String.join("\n",
				"//DESCRIPTION A script",
				"//GAV org.example:script:1.0",
				"//JAVA 17+",
				"//MAIN org.example.Main",
				"//MODULE mymod",
				"//DEPS org.example:one:1.0,org.example:two:2.0",
				"//DEPS Helper.java",
				"//REPOS central=https://repo1.maven.org/maven2/",
				"//SOURCES Other.java",
				"//COMPILE_OPTIONS -Xlint:all",
				"//RUNTIME_OPTIONS -Xmx64m",
				"//MANIFEST Add-Opens=java.base/java.lang",
				"//DOCS home=https://example.org",
				"//PREVIEW",
				"//CDS",
				"class Script {}"));

		assertThat(d.description(), is("A script"));
		assertThat(d.gav(), is("org.example:script:1.0"));
		assertThat(d.javaVersion(), is("17+"));
		assertThat(d.mainMethod(), is("org.example.Main"));
		assertThat(d.module(), is("mymod"));
		assertThat(d.binaryDependencies(), contains("org.example:one:1.0", "org.example:two:2.0"));
		assertThat(d.sourceDependencies(), contains("Helper.java"));
		assertThat(d.repositories().stream().map(r -> r.getId()).collect(Collectors.toList()),
				contains("central"));
		assertThat(d.sources(), contains("Other.java"));
		assertThat(d.compileOptions(), contains("-Xlint:all"));
		assertThat(d.runtimeOptions(), contains("-Xmx64m"));
		assertThat(d.enablePreview(), is(true));
		assertThat(d.enableCDS(), is(true));
		assertThat(d.isAgent(), is(false));
	}

	@Test
	void mapsKeyValueDirectivesToAttributes() {
		SourceDirectives d = parse(String.join("\n",
				"//MANIFEST Add-Opens=java.base/java.lang",
				"//MANIFEST Sealed",
				"//DOCS home=https://example.org",
				"class Script {}"));

		List<Attribute> manifest = d.manifestOptions();
		assertThat(manifest.stream().map(Attribute::key).collect(Collectors.toList()),
				containsInAnyOrder("Add-Opens", "Sealed"));
		Attribute sealed = manifest.stream().filter(a -> a.key().equals("Sealed")).findFirst().get();
		// a manifest entry without a value keeps a null value; what that means
		// is the caller's business, not the parser's
		assertThat(sealed.value(), is(nullValue()));

		assertThat(d.docs().size(), is(1));
		assertThat(d.docs().get(0).key(), is("home"));
		assertThat(d.docs().get(0).value(), is("https://example.org"));
	}

	@Test
	void agentDirectiveShowsUpAsAgentOptions() {
		SourceDirectives d = parse(String.join("\n",
				"//JAVAAGENT Can-Retransform-Classes=true",
				"class Script { public static void premain(String a) {} }"));

		assertThat(d.isAgent(), is(true));
		assertThat(d.agentOptions().size(), is(1));
		assertThat(d.agentOptions().get(0).key(), is("Can-Retransform-Classes"));
		assertThat(d.agentOptions().get(0).value(), is("true"));
	}

	@Test
	void fileRefsAreExpandedAgainstTheGivenDirectory(@TempDir Path dir) throws IOException {
		Files.write(dir.resolve("a.txt"), "a".getBytes(StandardCharsets.UTF_8));
		Files.write(dir.resolve("b.txt"), "b".getBytes(StandardCharsets.UTF_8));

		SourceDirectives d = parse(String.join("\n",
				"//FILES *.txt",
				"//FILES renamed.txt=a.txt",
				"class Script {}"));

		assertThat(d.fileRefs(dir), containsInAnyOrder("a.txt", "b.txt", "renamed.txt=a.txt"));
	}

	@Test
	void propertiesAreReplacedWhileParsing() {
		SourceDirectives d = parser().parse(String.join("\n",
				"//DEPS org.example:one:${ver}",
				"class Script {}"),
				value -> value.replace("${ver}", "9.9"));

		assertThat(d.binaryDependencies(), contains("org.example:one:9.9"));
	}

	@Test
	void aSourceWithoutDirectivesSaysSoWithoutFailing(@TempDir Path dir) {
		SourceDirectives d = parse("class Script {}");

		assertThat(d.binaryDependencies(), is(empty()));
		assertThat(d.sourceDependencies(), is(empty()));
		assertThat(d.repositories(), is(empty()));
		assertThat(d.sources(), is(empty()));
		assertThat(d.fileRefs(dir), is(empty()));
		assertThat(d.compileOptions(), is(empty()));
		assertThat(d.runtimeOptions(), is(empty()));
		assertThat(d.manifestOptions(), is(empty()));
		assertThat(d.agentOptions(), is(empty()));
		assertThat(d.docs(), is(empty()));
		assertThat(d.javaVersion(), is(nullValue()));
		assertThat(d.gav(), is(nullValue()));
		assertThat(d.description(), is(nullValue()));
		assertThat(d.module(), is(nullValue()));
		assertThat(d.isAgent(), is(false));
		assertThat(d.enableCDS(), is(false));
		assertThat(d.enablePreview(), is(false));
	}
}
