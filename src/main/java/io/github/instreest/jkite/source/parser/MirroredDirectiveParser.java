package io.github.instreest.jkite.source.parser;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.jbang.dependencies.MavenRepo;
import io.github.instreest.jkite.spi.Attribute;
import io.github.instreest.jkite.spi.DirectiveParser;
import io.github.instreest.jkite.spi.SourceDirectives;
import dev.jbang.source.parser.Directives;
import dev.jbang.source.parser.KeyValue;

/**
 * {@link DirectiveParser} on {@link Directives}, the copy of JBang's parser in
 * this package.
 *
 * It is the only place that names {@link Directives} and {@link KeyValue}: this
 * class maps them to the types of {@code dev.jbang.spi}, so the mirrored files
 * stay a detail of the implementation rather than jkite's own API.
 */
public final class MirroredDirectiveParser implements DirectiveParser {

	@Override
	public SourceDirectives parse(String content, Function<String, String> propertyReplacer) {
		return new Parsed(new Directives.Extended(content, propertyReplacer));
	}

	private static final class Parsed implements SourceDirectives {
		private final Directives directives;

		Parsed(Directives directives) {
			this.directives = directives;
		}

		@Override
		public List<String> binaryDependencies() {
			return directives.binaryDependencies();
		}

		@Override
		public List<String> sourceDependencies() {
			return directives.sourceDependencies();
		}

		@Override
		public List<MavenRepo> repositories() {
			return directives.repositories();
		}

		@Override
		public List<String> sources() {
			return directives.sources();
		}

		@Override
		public List<String> fileRefs(Path baseDir) {
			return directives.files()
				.stream()
				.flatMap(kv -> Directives.explodeFileRef(null, baseDir, kv).stream())
				.collect(Collectors.toList());
		}

		@Override
		public String javaVersion() {
			return directives.javaVersion();
		}

		@Override
		public String mainMethod() {
			return directives.mainMethod();
		}

		@Override
		public String module() {
			return directives.module();
		}

		@Override
		public String gav() {
			return directives.gav();
		}

		@Override
		public String description() {
			return directives.description();
		}

		@Override
		public List<Attribute> manifestOptions() {
			return attributes(directives.manifestOptions());
		}

		@Override
		public List<Attribute> agentOptions() {
			return attributes(directives.agentOptions());
		}

		@Override
		public List<Attribute> docs() {
			return attributes(directives.collectDocs());
		}

		@Override
		public boolean isAgent() {
			return directives.isAgent();
		}

		@Override
		public boolean enableCDS() {
			return directives.enableCDS();
		}

		@Override
		public boolean enablePreview() {
			return directives.enablePreview();
		}

		@Override
		public List<String> compileOptions() {
			return directives.compileOptions();
		}

		@Override
		public List<String> runtimeOptions() {
			return directives.runtimeOptions();
		}

		private static List<Attribute> attributes(List<KeyValue> keyValues) {
			return keyValues.stream()
				.map(kv -> new Attribute(kv.getKey(), kv.getValue()))
				.collect(Collectors.toList());
		}
	}
}
