package io.github.instreest.jkite.spi;

import java.util.Objects;

/**
 * A <code>key=value</code> pair read from a directive, e.g. an entry of
 * <code>//MANIFEST</code> or <code>//DOCS</code>.
 *
 * jkite's own type: the parser behind {@link DirectiveParser} has one of
 * its own (JBang's <code>KeyValue</code>), and this is what it is mapped to so
 * that no type of the parser reaches the rest of the code.
 */
public final class Attribute {
	private final String key;
	private final String value;

	public Attribute(String key, String value) {
		this.key = Objects.requireNonNull(key);
		this.value = value;
	}

	public String key() {
		return key;
	}

	/** The value, or null when the directive gave none. */
	public String value() {
		return value;
	}

	@Override
	public String toString() {
		return value != null ? key + "=" + value : key;
	}
}
