package io.github.instreest.jkite.util;

import java.util.Locale;

import dev.jbang.ExitException;
import io.github.instreest.jkite.Settings;
import io.github.instreest.jkite.spi.DownloadGate;
import dev.jbang.util.Util;

/**
 * The {@link DownloadGate} jkite uses: it says what is about to be
 * downloaded and, when there is a terminal to ask on, waits for an answer.
 *
 * JKITE_CONFIRM_DOWNLOADS decides:
 * <dl>
 * <dt>auto (the default)</dt>
 * <dd>ask when there is a terminal, otherwise say what is happening and go
 * ahead. A build that runs unattended is never left waiting for an answer
 * nobody is there to give.</dd>
 * <dt>always</dt>
 * <dd>ask, and refuse the download when there is no terminal. For a project
 * that means to bring everything it needs with it.</dd>
 * <dt>never</dt>
 * <dd>never ask, like <code>--yes</code> and JKITE_ASSUME_YES.</dd>
 * </dl>
 *
 * "A terminal" means one that can actually be opened, which
 * {@link Util#askOnTerminal(String)} does directly. {@link System#console()} is
 * not used for this: since Java 22 it is non-null even when stdin is a pipe, so
 * it would report a terminal where there is none and then read the answer out of
 * the script's own input. jkite installs a JDK far newer than 22, so that is
 * the usual case rather than an edge one.
 *
 * The question and everything around it go to the terminal and to stderr, never
 * to stdout, so a pipeline built on a script's output is unaffected. Answering
 * with Enter accepts: the gate is there to say what is about to happen, not to
 * make every first run fail.
 */
public final class PromptingDownloadGate implements DownloadGate {

	/** Asks a question on the terminal and returns the answer, or null on EOF. */
	public interface Prompter {
		String ask(String message, String question);
	}

	private final String mode;
	private final boolean assumeYes;
	private final Prompter prompter;

	public PromptingDownloadGate() {
		this(Settings.getConfirmDownloads(), Settings.isAssumeYes(), terminalPrompter());
	}

	/**
	 * @param prompter the terminal to ask on, or null when there is none
	 */
	PromptingDownloadGate(String mode, boolean assumeYes, Prompter prompter) {
		this.mode = mode;
		this.assumeYes = assumeYes;
		this.prompter = prompter;
	}

	@Override
	public void check(Request request) {
		if (assumeYes || "never".equals(mode)) {
			return;
		}
		boolean always = "always".equals(mode);
		if (!always && !"auto".equals(mode)) {
			Util.warnMsg("Ignoring invalid " + Settings.ENV_CONFIRM_DOWNLOADS + ": " + mode);
		}
		String message = describe(request);
		if (prompter == null) {
			if (always) {
				throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
						message + System.lineSeparator()
								+ "Refusing to download without confirmation, and there is no terminal to ask on ("
								+ Settings.ENV_CONFIRM_DOWNLOADS + "=always). Pass --yes or set "
								+ Settings.ENV_ASSUME_YES + "=1 to allow it.");
			}
			// nobody to ask: say what is happening and carry on
			Util.infoMsg(message);
			return;
		}
		String answer = prompter.ask(message, "Continue? [Y/n] ");
		if (answer != null && !accepted(answer)) {
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR, "Download declined");
		}
	}

	private static Prompter terminalPrompter() {
		if (!Util.hasTerminal()) {
			return null;
		}
		return (message, question) -> {
			System.err.println(message);
			return Util.askOnTerminal(question);
		};
	}

	private static boolean accepted(String answer) {
		String a = answer.trim().toLowerCase(Locale.ROOT);
		return a.isEmpty() || a.equals("y") || a.equals("yes");
	}

	private static String describe(Request request) {
		StringBuilder sb = new StringBuilder(request.summary());
		for (String item : request.items()) {
			sb.append(System.lineSeparator()).append("   ").append(item);
		}
		return sb.toString();
	}
}
