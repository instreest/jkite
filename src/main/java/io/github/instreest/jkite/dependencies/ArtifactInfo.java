package io.github.instreest.jkite.dependencies;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.instreest.jkite.util.Fingerprint;
import dev.jbang.util.Util;
import dev.jbang.dependencies.MavenCoordinate;

/**
 * A resolved artifact: its coordinate, the local file it resolved to, and the
 * fingerprint that file had when it was resolved.
 *
 * A cached resolution is a list of these, and it is worth reusing only while
 * every file in it is still the file the resolution produced. That is what
 * {@link #isUpToDate()} asks, and it asks it of the bytes: see
 * {@link Fingerprint} for why the modification time is no answer.
 */
public final class ArtifactInfo {
	private final MavenCoordinate coordinate;
	private final Path file;
	private final Fingerprint fingerprint;

	public ArtifactInfo(MavenCoordinate coordinate, Path file) {
		this(coordinate, file, fingerprintOf(file));
	}

	public ArtifactInfo(MavenCoordinate coordinate, Path file, Fingerprint fingerprint) {
		this.coordinate = coordinate;
		this.file = file;
		this.fingerprint = fingerprint;
	}

	private static Fingerprint fingerprintOf(Path file) {
		try {
			return Fingerprint.of(file);
		} catch (IOException | RuntimeException e) {
			// a resolution that produced a file we cannot read is already
			// wrong; say so where it is used rather than failing here
			Util.verboseMsg("Could not read the resolved " + file + ": " + e);
			return null;
		}
	}

	public MavenCoordinate getCoordinate() {
		return coordinate;
	}

	public Path getFile() {
		return file;
	}

	/** What the file held when it was resolved, or null if it could not be read. */
	public Fingerprint getFingerprint() {
		return fingerprint;
	}

	/** True while the file is still the one this artifact was resolved to. */
	public boolean isUpToDate() {
		return fingerprint != null ? fingerprint.matches(file) : Files.isReadable(file);
	}

	@Override
	public String toString() {
		return (coordinate == null ? "<null>" : coordinate.toMavenString()) + "=" + file.toAbsolutePath();
	}
}
