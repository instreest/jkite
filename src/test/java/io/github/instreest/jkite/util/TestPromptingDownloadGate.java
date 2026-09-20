package io.github.instreest.jkite.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.jbang.ExitException;
import io.github.instreest.jkite.spi.DownloadGate;

class TestPromptingDownloadGate {

	private static final DownloadGate.Request DEPS = new DownloadGate.Request(DownloadGate.Kind.DEPENDENCIES,
			"Dependencies are missing locally and will be downloaded:",
			Arrays.asList("org.example:one:1.0", "org.example:two:2.0"));

	/** A terminal that answers what it was given and records what it was asked. */
	private static final class FakePrompter implements PromptingDownloadGate.Prompter {
		final List<String> messages = new ArrayList<>();
		private final String answer;

		FakePrompter(String answer) {
			this.answer = answer;
		}

		@Override
		public String ask(String message, String question) {
			messages.add(message);
			return answer;
		}
	}

	@Test
	void asksOnATerminalAndAcceptsYes() {
		FakePrompter prompter = new FakePrompter("y");
		new PromptingDownloadGate("auto", false, prompter).check(DEPS);
		assertThat(prompter.messages.size(), is(1));
		assertThat(prompter.messages.get(0), containsString("org.example:one:1.0"));
		assertThat(prompter.messages.get(0), containsString("org.example:two:2.0"));
	}

	@Test
	void emptyAnswerAccepts() {
		new PromptingDownloadGate("auto", false, new FakePrompter("")).check(DEPS);
	}

	@Test
	void endOfInputAccepts() {
		new PromptingDownloadGate("auto", false, new FakePrompter(null)).check(DEPS);
	}

	@Test
	void anythingElseDeclines() {
		ExitException e = assertThrows(ExitException.class,
				() -> new PromptingDownloadGate("auto", false, new FakePrompter("n")).check(DEPS));
		assertThat(e.getMessage(), containsString("declined"));
	}

	@Test
	void withoutATerminalAutoGoesAhead() {
		new PromptingDownloadGate("auto", false, null).check(DEPS);
	}

	@Test
	void withoutATerminalAlwaysRefuses() {
		ExitException e = assertThrows(ExitException.class,
				() -> new PromptingDownloadGate("always", false, null).check(DEPS));
		assertThat(e.getMessage(), containsString("no terminal"));
		assertThat(e.getMessage(), containsString("--yes"));
	}

	@Test
	void neverDoesNotAskEvenOnATerminal() {
		FakePrompter prompter = new FakePrompter("n");
		new PromptingDownloadGate("never", false, prompter).check(DEPS);
		assertThat(prompter.messages.isEmpty(), is(true));
	}

	@Test
	void assumeYesDoesNotAskEvenWhenAlwaysIsAsked() {
		FakePrompter prompter = new FakePrompter("n");
		new PromptingDownloadGate("always", true, prompter).check(DEPS);
		assertThat(prompter.messages.isEmpty(), is(true));
	}

	@Test
	void anUnknownModeBehavesLikeAuto() {
		FakePrompter prompter = new FakePrompter("y");
		new PromptingDownloadGate("sometimes", false, prompter).check(DEPS);
		assertThat(prompter.messages.size(), is(1));
	}

	@Test
	void aRequestWithoutItemsIsJustItsSummary() {
		FakePrompter prompter = new FakePrompter("y");
		new PromptingDownloadGate("auto", false, prompter)
			.check(new DownloadGate.Request(DownloadGate.Kind.JDK, "No JDK was found.", Collections.emptyList()));
		assertThat(prompter.messages.get(0), is("No JDK was found."));
	}

	@Test
	void allowLetsEverythingThrough() {
		DownloadGate.ALLOW.check(DEPS);
	}
}
