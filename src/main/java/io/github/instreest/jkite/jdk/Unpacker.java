package io.github.instreest.jkite.jdk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import dev.jbang.util.Util;

/**
 * Unpacks JDK archives (.zip and .tar.gz). The single root folder of the
 * archive is stripped and on macOS the <code>Contents/Home</code> folder is
 * selected.
 *
 * The archive formats themselves are read by Commons Compress rather than by
 * hand: a JDK distribution is a real-world tar, with pax and GNU extensions for
 * long names, links and permissions, and getting those subtly wrong writes
 * wrong files instead of failing.
 *
 * Nothing is written outside the output directory. An entry whose path leads
 * out of it, and a link whose target does, are refused rather than skipped: a
 * JDK archive has no business containing either, so it is not a JDK we should
 * be installing.
 */
final class Unpacker {
	private Unpacker() {
	}

	static void unpackJdk(Path archive, Path outputDir) throws IOException {
		String name = archive.getFileName().toString().toLowerCase(Locale.ENGLISH);
		Path selectFolder = Util.isMac() ? Paths.get("Contents/Home") : null;
		if (name.endsWith(".zip")) {
			unzip(archive, outputDir, selectFolder);
		} else if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
			untargz(archive, outputDir, selectFolder);
		} else {
			throw new IOException("Unsupported archive format: " + archive);
		}
	}

	/**
	 * Where an entry ends up, or null when it is not to be unpacked at all (the
	 * root folder itself, or anything outside the selected folder).
	 */
	private static Path targetPath(String entryName, Path outputDir, Path selectFolder) throws IOException {
		Path entry = Paths.get(entryName).normalize();
		if (entry.getNameCount() <= 1) {
			return null; // root folder itself
		}
		entry = entry.subpath(1, entry.getNameCount());
		// Before the folder this platform selects is looked at, so that an entry
		// which climbs out is refused everywhere rather than refused on Linux
		// and quietly dropped on macOS, where the Contents/Home filter would
		// have reached it first. What is refused must not depend on the host.
		if (entry.startsWith("..")) {
			throw new IOException("Entry is outside of the target dir: " + entryName);
		}
		if (selectFolder != null) {
			if (!entry.startsWith(selectFolder) || entry.equals(selectFolder)) {
				return null;
			}
			entry = entry.subpath(selectFolder.getNameCount(), entry.getNameCount());
		}
		Path out = outputDir.resolve(entry).normalize();
		if (!out.startsWith(outputDir)) {
			throw new IOException("Entry is outside of the target dir: " + entryName);
		}
		return out;
	}

	private static void unzip(Path zip, Path outputDir, Path selectFolder) throws IOException {
		try (ZipFile zipFile = ZipFile.builder().setPath(zip).get()) {
			Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
			while (entries.hasMoreElements()) {
				ZipArchiveEntry ze = entries.nextElement();
				Path out = targetPath(ze.getName(), outputDir, selectFolder);
				if (out == null) {
					continue;
				}
				if (ze.isDirectory()) {
					Files.createDirectories(out);
					continue;
				}
				createParent(out, outputDir);
				if (ze.isUnixSymlink()) {
					link(out, zipFile.getUnixSymlink(ze), outputDir);
					continue;
				}
				try (InputStream is = zipFile.getInputStream(ze)) {
					Files.copy(is, out, StandardCopyOption.REPLACE_EXISTING);
				}
				int mode = ze.getUnixMode();
				if (mode != 0) {
					setPermissions(out, mode);
				} else if (!Util.isWindows() && out.getParent().getFileName().toString().equals("bin")) {
					// a zip written without unix modes at all: keep bin/* usable
					out.toFile().setExecutable(true, false);
				}
			}
		}
	}

	private static void untargz(Path targz, Path outputDir, Path selectFolder) throws IOException {
		try (TarArchiveInputStream tar = new TarArchiveInputStream(
				new GzipCompressorInputStream(Files.newInputStream(targz)))) {
			TarArchiveEntry te;
			while ((te = tar.getNextEntry()) != null) {
				Path out = targetPath(te.getName(), outputDir, selectFolder);
				if (out == null) {
					continue;
				}
				if (te.isDirectory()) {
					Files.createDirectories(out);
					continue;
				}
				if (te.isSymbolicLink() || te.isLink()) {
					createParent(out, outputDir);
					link(out, te.getLinkName(), outputDir);
					continue;
				}
				if (te.isCharacterDevice() || te.isBlockDevice() || te.isFIFO()) {
					// device nodes and pipes are not part of a JDK
					continue;
				}
				createParent(out, outputDir);
				Files.copy(tar, out, StandardCopyOption.REPLACE_EXISTING);
				setPermissions(out, te.getMode());
			}
		}
	}

	/**
	 * Creates the directories above an entry, and refuses to write through a
	 * symbolic link that leads out of the output directory: an earlier entry may
	 * have created one, and following it would put the file anywhere.
	 */
	private static void createParent(Path out, Path outputDir) throws IOException {
		Path parent = out.getParent();
		Files.createDirectories(parent);
		if (!parent.toRealPath().startsWith(outputDir.toRealPath())) {
			throw new IOException("Entry leads outside of the target dir through a link: " + out);
		}
	}

	/**
	 * Creates a link, as long as it stays inside the output directory. Hard
	 * links are made symbolic, which is all a JDK layout needs them for.
	 */
	private static void link(Path out, String linkName, Path outputDir) throws IOException {
		if (linkName == null || linkName.isEmpty()) {
			return;
		}
		Path target = out.getParent().resolve(linkName).normalize();
		if (!target.startsWith(outputDir)) {
			throw new IOException("Link " + out + " points outside of the target dir: " + linkName);
		}
		if (Files.exists(out, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try {
			Files.createSymbolicLink(out, Paths.get(linkName));
		} catch (IOException | UnsupportedOperationException e) {
			// Windows without the privilege, or a file system without links
			Util.verboseMsg("Could not create link " + out + " -> " + linkName + ": " + e);
		}
	}

	private static void setPermissions(Path out, int mode) {
		if (mode == 0 || Util.isWindows()) {
			return;
		}
		try {
			Files.setPosixFilePermissions(out, toPosix(mode));
		} catch (IOException | UnsupportedOperationException e) {
			// a non-POSIX file system: the default permissions have to do
			Util.verboseMsg("Could not set the permissions of " + out + ": " + e);
		}
	}

	private static Set<PosixFilePermission> toPosix(int mode) {
		Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
		PosixFilePermission[] all = { PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE,
				PosixFilePermission.OTHERS_READ, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE,
				PosixFilePermission.GROUP_READ, PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE,
				PosixFilePermission.OWNER_READ };
		for (int i = 0; i < all.length; i++) {
			if ((mode & (1 << i)) != 0) {
				perms.add(all[i]);
			}
		}
		return perms;
	}
}
