package io.github.instreest.jkite.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.jbang.util.Util;

/**
 * What a file is, as far as a cache has to know: how long it is and what it
 * holds. A cache entry that names a file records the file's fingerprint, and
 * uses the entry only while the file still matches it.
 *
 * The modification time is deliberately not part of it. It is metadata about
 * when a file was written rather than about what is in it, and it moves on its
 * own in all the ways a cache meets:
 * <ul>
 * <li>a CI job restoring <code>~/.m2</code> from its cache gives every file the
 * time of the restore, so every artifact looks new and every resolution is done
 * again, on every build;</li>
 * <li><code>mvn install</code> of an unchanged module, a fresh checkout, a copy
 * without <code>-p</code>, a container layer being unpacked - all of them
 * rewrite the time and none of them change a byte;</li>
 * <li>and it can stand still while the content moves: a build that preserves
 * timestamps, or two writes inside the same second on a filesystem that stores
 * whole seconds.</li>
 * </ul>
 * The first three cost work that was not needed - a re-resolution, a download,
 * a question to the operator. The last one hands out a class path that is not
 * what it says it is, which is worse, and the second-granularity tolerance that
 * a time comparison needs makes that window a whole second wide.
 *
 * The size is checked first because it settles most cases with one call to the
 * filesystem; the digest is what settles the rest. Hashing is not free - about
 * 30 ms for a 22 MB class path here - but it is paid only where a cache is
 * about to be trusted, and it is the only answer that does not depend on
 * something other than the bytes.
 */
public final class Fingerprint {
	private static final Pattern SYNTAX = Pattern.compile("(\\d+):([0-9a-f]{64})");

	private final long size;
	private final String sha256;

	private Fingerprint(long size, String sha256) {
		this.size = size;
		this.sha256 = sha256;
	}

	/** Reads the file and takes its fingerprint. */
	public static Fingerprint of(Path file) throws IOException {
		return new Fingerprint(Files.size(file), Util.sha256(file));
	}

	/** The fingerprint {@link #toString()} wrote, or null when it is not one. */
	public static Fingerprint parse(String text) {
		Matcher m = text != null ? SYNTAX.matcher(text) : null;
		if (m == null || !m.matches()) {
			return null;
		}
		try {
			return new Fingerprint(Long.parseLong(m.group(1)), m.group(2));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** True when the file is there and is still the one this was taken of. */
	public boolean matches(Path file) {
		try {
			return Files.isRegularFile(file) && Files.size(file) == size && sha256.equals(Util.sha256(file));
		} catch (IOException | RuntimeException e) {
			Util.verboseMsg("Could not read " + file + " to check it: " + e);
			return false;
		}
	}

	public long size() {
		return size;
	}

	public String sha256() {
		return sha256;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Fingerprint && size == ((Fingerprint) o).size && sha256.equals(((Fingerprint) o).sha256);
	}

	@Override
	public int hashCode() {
		return Objects.hash(size, sha256);
	}

	@Override
	public String toString() {
		return size + ":" + sha256;
	}
}
