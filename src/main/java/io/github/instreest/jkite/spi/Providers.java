package io.github.instreest.jkite.spi;

import java.util.Objects;

import io.github.instreest.jkite.source.parser.MirroredDirectiveParser;
import io.github.instreest.jkite.util.PromptingDownloadGate;

/**
 * Where the implementations behind the interfaces of this package are chosen.
 *
 * One place, on purpose: swapping the directive parser for one on a JBang
 * library artifact, or the download gate for one that never asks, is a change
 * here and nowhere else. Tests set what they need and restore it afterwards.
 */
public final class Providers {
	private static volatile DirectiveParser directiveParser = new MirroredDirectiveParser();
	private static volatile DownloadGate downloadGate = new PromptingDownloadGate();

	private Providers() {
	}

	public static DirectiveParser directiveParser() {
		return directiveParser;
	}

	public static void setDirectiveParser(DirectiveParser parser) {
		directiveParser = Objects.requireNonNull(parser);
	}

	public static DownloadGate downloadGate() {
		return downloadGate;
	}

	public static void setDownloadGate(DownloadGate gate) {
		downloadGate = Objects.requireNonNull(gate);
	}
}
