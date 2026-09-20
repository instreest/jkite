package io.github.instreest.jkite.dependencies;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.github.instreest.jkite.Settings;
import io.github.instreest.jkite.util.CacheLock;
import io.github.instreest.jkite.util.Fingerprint;
import dev.jbang.util.Util;
import dev.jbang.dependencies.MavenCoordinate;

/**
 * Simple on-disk cache of resolved class paths so that scripts start without
 * touching the Maven resolver. Format (one entry per line):
 *
 * <pre>
 * [key]
 * coordinate&lt;TAB&gt;file&lt;TAB&gt;size:sha-256
 * </pre>
 *
 * The third field is the {@link Fingerprint} the file had when it was
 * resolved, which is what says whether the entry still describes what is on
 * disk. An entry written by an older version records a modification time
 * there instead; it does not parse as a fingerprint, so the entry is dropped
 * and resolved once more.
 *
 * The file holds the entries of every script on this machine, so a run that
 * stores its own must not lose anyone else's: it re-reads the file and writes
 * the merged result, under a lock, rather than writing back the copy it read
 * when it started.
 */
final class DependencyCache {
	/** The name of the lock taken while the file is rewritten. */
	private static final String LOCK = "dependency_cache";

	private static Map<String, List<ArtifactInfo>> cache;

	private DependencyCache() {
	}

	private static Map<String, List<ArtifactInfo>> load() {
		if (cache == null) {
			cache = read(Settings.getDependencyCacheFile());
		}
		return cache;
	}

	/**
	 * Reads the file. A line that cannot be read takes its whole entry with it:
	 * a class path with one artifact missing still looks usable and would fail
	 * much later, when the script cannot find a class.
	 */
	static Map<String, List<ArtifactInfo>> read(Path file) {
		Map<String, List<ArtifactInfo>> entries = new LinkedHashMap<>();
		if (!Files.isRegularFile(file)) {
			return entries;
		}
		try (BufferedReader rdr = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;
			String key = null;
			List<ArtifactInfo> current = null;
			while ((line = rdr.readLine()) != null) {
				if (line.isEmpty()) {
					continue;
				}
				if (line.startsWith("[") && line.endsWith("]")) {
					key = line.substring(1, line.length() - 1);
					current = new ArrayList<>();
					entries.put(key, current);
				} else if (current != null) {
					ArtifactInfo artifact = parse(line);
					if (artifact == null) {
						Util.verboseMsg("Dropping the damaged entry [" + key + "] of " + file + ": " + line);
						entries.remove(key);
						current = null;
					} else {
						current.add(artifact);
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			Util.warnMsg("Ignoring unreadable dependency cache " + file + ": " + e.getMessage());
			return new LinkedHashMap<>();
		}
		return entries;
	}

	/** One artifact line, or null when it is not one. */
	private static ArtifactInfo parse(String line) {
		String[] parts = line.split("\t");
		if (parts.length != 3) {
			return null;
		}
		try {
			Fingerprint fingerprint = Fingerprint.parse(parts[2]);
			if (fingerprint == null) {
				return null;
			}
			MavenCoordinate coord = parts[0].isEmpty() ? null : MavenCoordinate.fromString(parts[0]);
			return new ArtifactInfo(coord, Paths.get(parts[1]), fingerprint);
		} catch (RuntimeException e) {
			return null;
		}
	}

	static List<ArtifactInfo> find(String key) {
		List<ArtifactInfo> cached = load().get(key);
		if (cached != null) {
			if (cached.stream().allMatch(ArtifactInfo::isUpToDate)) {
				return cached;
			}
			Util.warnMsg("Detected missing or out-of-date dependencies in cache.");
			if (Util.isVerbose()) {
				cached.stream().filter(ai -> !ai.isUpToDate())
					.forEach(ai -> Util.verboseMsg("   Artifact missing or out of date: " + ai.getFile()));
			}
		}
		return null;
	}

	static void store(String key, List<ArtifactInfo> artifacts) {
		try (CacheLock lock = CacheLock.acquire(LOCK, null)) {
			cache = merge(Settings.getDependencyCacheFile(), key, artifacts);
		}
	}

	/**
	 * Adds one entry to what the file holds <em>now</em> and writes the result
	 * back, rather than to the copy this run read when it started: another run
	 * may have stored its own entry in between, and that one is in the file but
	 * not in our copy of it.
	 */
	static Map<String, List<ArtifactInfo>> merge(Path file, String key, List<ArtifactInfo> artifacts) {
		Map<String, List<ArtifactInfo>> entries = read(file);
		if (artifacts.stream().allMatch(a -> a.getFingerprint() != null)) {
			entries.put(key, artifacts);
		} else {
			// an entry whose files could not be read is an entry that can never
			// be checked again; resolving once more is the cheaper mistake
			Util.verboseMsg("Not caching [" + key + "]: not every artifact could be read");
			entries.remove(key);
		}
		write(entries, file);
		return entries;
	}

	private static void write(Map<String, List<ArtifactInfo>> entries, Path file) {
		try {
			Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
			try {
				try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
					for (Map.Entry<String, List<ArtifactInfo>> e : entries.entrySet()) {
						out.write("[" + e.getKey() + "]\n");
						for (ArtifactInfo ai : e.getValue()) {
							String coord = ai.getCoordinate() != null ? ai.getCoordinate().toMavenString() : "";
							out.write(coord + "\t" + ai.getFile() + "\t" + ai.getFingerprint() + "\n");
						}
						out.write("\n");
					}
				}
				try {
					Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				} catch (AtomicMoveNotSupportedException e) {
					Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
				}
			} finally {
				Util.deletePath(tmp, true);
			}
		} catch (IOException e) {
			Util.errorMsg("Issue writing to dependency cache", e);
		}
	}
}
