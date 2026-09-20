package io.github.instreest.jkite.spi;

import java.util.function.Function;

/**
 * Reads the <code>//</code>-directives of a source file.
 *
 * The one thing jkite takes from JBang is how these are written down, so
 * this is the interface across which JBang sits. The implementation in use is
 * {@code MirroredDirectiveParser}, on the copy of JBang's parser in
 * {@code dev.jbang.source.parser}.
 *
 * A second implementation on JBang's own artifact ({@code dev.jbang:jbang.bin},
 * which is published with every release and whose {@code Directives} is
 * identical to the copy here) can be dropped in at {@link Providers} without
 * anything above this interface changing, and both are held to the same test.
 * That it is not the implementation in use is a decision rather than a
 * limitation: the artifact brings the whole CLI's dependencies, where the parse
 * path needs none of them, and syncing whole files costs less than carrying
 * them. See {@code misc/library-boundary-analysis.md}.
 */
public interface DirectiveParser {

	/**
	 * Parses the contents of one source file.
	 *
	 * @param content          the whole file
	 * @param propertyReplacer expands <code>${property}</code> in a directive's
	 *                         value; applied by the parser as it reads them
	 */
	SourceDirectives parse(String content, Function<String, String> propertyReplacer);
}
