package io.github.instreest.jkite.spi;

import java.nio.file.Path;
import java.util.List;

import dev.jbang.dependencies.MavenRepo;

/**
 * The <code>//</code>-directives of one source file, as jkite needs them.
 *
 * This is the boundary towards JBang: {@link DirectiveParser} produces it, and
 * nothing above it knows which parser did. The types here are jkite's own,
 * with one deliberate exception: {@link MavenRepo} is a plain value type shared
 * with the dependency resolver, and duplicating it would buy nothing.
 *
 * A method returns what the directives of this one file say; a file that has
 * none returns an empty list or null, never an error. Combining the directives
 * of several files (a main file and its <code>//SOURCES</code>) is the caller's
 * job, see {@code Project}.
 */
public interface SourceDirectives {

	/** <code>//DEPS</code> entries that are Maven coordinates. */
	List<String> binaryDependencies();

	/** <code>//DEPS</code> entries that name another source file. */
	List<String> sourceDependencies();

	/** <code>//REPOS</code>, including <code>@GrabResolver</code>. */
	List<MavenRepo> repositories();

	/** <code>//SOURCES</code> patterns, not yet resolved against a directory. */
	List<String> sources();

	/**
	 * <code>//FILES</code> entries with their globs expanded against
	 * <code>baseDir</code>, each still in <code>[target=]source</code> form.
	 */
	List<String> fileRefs(Path baseDir);

	/** <code>//JAVA</code>, e.g. "17" or "17+", or null. */
	String javaVersion();

	/** <code>//MAIN</code> or a main method found in the source, or null. */
	String mainMethod();

	/** <code>//MODULE</code>: the name, "" for the directive without one, or null. */
	String module();

	/** <code>//GAV</code>, or null. */
	String gav();

	/** <code>//DESCRIPTION</code>, or null. */
	String description();

	/** <code>//MANIFEST</code> entries. */
	List<Attribute> manifestOptions();

	/** The <code>key=value</code> options given on <code>//JAVAAGENT</code>. */
	List<Attribute> agentOptions();

	/** <code>//DOCS</code> entries. */
	List<Attribute> docs();

	/** Whether <code>//JAVAAGENT</code> is present. */
	boolean isAgent();

	/** Whether <code>//CDS</code> is present. */
	boolean enableCDS();

	/** Whether <code>//PREVIEW</code> is present. */
	boolean enablePreview();

	/** <code>//COMPILE_OPTIONS</code> and <code>//JAVAC_OPTIONS</code>. */
	List<String> compileOptions();

	/** <code>//RUNTIME_OPTIONS</code> and <code>//JAVA_OPTIONS</code>. */
	List<String> runtimeOptions();
}
