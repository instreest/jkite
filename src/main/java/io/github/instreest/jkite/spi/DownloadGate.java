package io.github.instreest.jkite.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Asked before anything is fetched from the network, so that a run never
 * downloads silently.
 *
 * Every automatic download jkite makes passes through here first: the JDK
 * a script asks for with <code>//JAVA</code> (the JVM index included) and the
 * dependencies of <code>//DEPS</code>. The gate is asked only when something
 * really is missing locally - a JDK that is already installed or a dependency
 * that is already resolved never reaches it - so saying yes once is not
 * answered again on the next run.
 *
 * An implementation either returns (the download may go ahead) or throws, which
 * ends the run. {@code PromptingDownloadGate} is the one in use; {@link #ALLOW}
 * is the one for <code>--yes</code> and for tests.
 */
public interface DownloadGate {

	/** What is about to be downloaded. */
	enum Kind {
		/** A JDK, and the index that says where to get it. */
		JDK,
		/** Maven artifacts for //DEPS. */
		DEPENDENCIES
	}

	/** One pending download, as it is put to the user. */
	final class Request {
		private final Kind kind;
		private final String summary;
		private final List<String> items;

		public Request(Kind kind, String summary, List<String> items) {
			this.kind = Objects.requireNonNull(kind);
			this.summary = Objects.requireNonNull(summary);
			this.items = Collections.unmodifiableList(new ArrayList<>(items));
		}

		public Kind kind() {
			return kind;
		}

		/** One line saying what will happen and why. */
		public String summary() {
			return summary;
		}

		/** What is to be fetched, as far as it is known before fetching it. */
		public List<String> items() {
			return items;
		}
	}

	/**
	 * Called before the download starts.
	 *
	 * @throws dev.jbang.ExitException when the download is not to happen
	 */
	void check(Request request);

	/** Lets everything through without asking. */
	DownloadGate ALLOW = request -> {
	};
}
