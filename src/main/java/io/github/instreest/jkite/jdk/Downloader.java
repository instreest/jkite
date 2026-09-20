package io.github.instreest.jkite.jdk;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import io.github.instreest.jkite.Settings;
import dev.jbang.util.Util;
import io.github.instreest.jkite.Version;

/**
 * Minimal HTTPS downloader for the JDK path, and for nothing else. Every URL
 * it is given and every redirect it follows goes through {@link JdkSource}, so
 * a caller cannot reach a host the JDK policy would refuse and a redirect
 * cannot move a download off https or off GitHub. Failed transfers are retried
 * with the same backoff the launcher scripts use, controlled by
 * JKITE_DOWNLOAD_RETRY and JKITE_DOWNLOAD_RETRY_DELAY.
 */
final class Downloader {
	private static final int MAX_REDIRECTS = 10;
	private static final int CONNECT_TIMEOUT = 30_000;
	private static final int READ_TIMEOUT = 120_000;

	private Downloader() {
	}

	/** Downloads a URL into the given file, retrying on failure. */
	static void download(String url, Path target) throws IOException {
		int maxAttempts = Math.max(0, Settings.getDownloadRetry()) + 1;
		int delay = Settings.getDownloadRetryDelay();
		IOException last = null;
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				transfer(url, target);
				return;
			} catch (IOException e) {
				last = e;
				if (attempt >= maxAttempts) {
					break;
				}
				int seconds = delay > 0 ? delay : 1 << (attempt - 1);
				Util.warnMsg("Download " + attempt + "/" + maxAttempts + " failed (" + e.getMessage()
						+ "). Retry in " + seconds + " second(s)...");
				if (attempt == 1) {
					Util.infoMsg("(Set " + Settings.ENV_DOWNLOAD_RETRY + "=0 to disable retries)");
				}
				try {
					Thread.sleep(seconds * 1000L);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while waiting to retry " + url, ie);
				}
			}
		}
		throw last;
	}

	/**
	 * Reads a small text resource, e.g. a checksum file. Returns empty when it
	 * does not exist or could not be read; the caller decides how bad that is.
	 */
	static Optional<String> tryReadString(String url) {
		try {
			Path tmp = Files.createTempFile("jkite", ".txt");
			try {
				transfer(url, tmp);
				return Optional.of(new String(Files.readAllBytes(tmp), java.nio.charset.StandardCharsets.UTF_8));
			} finally {
				Util.deletePath(tmp, true);
			}
		} catch (IOException e) {
			Util.verboseMsg("Could not read " + url + ": " + e);
			return Optional.empty();
		}
	}

	private static void transfer(String url, Path target) throws IOException {
		String current = url;
		// again here, and not only where the entry is chosen: this is the door
		// itself, so no caller can reach a host the JDK policy would refuse
		JdkSource.requireIndexUrl(current, "to download");
		for (int i = 0; i < MAX_REDIRECTS; i++) {
			HttpURLConnection conn = (HttpURLConnection) toUrl(current).openConnection();
			conn.setInstanceFollowRedirects(false);
			conn.setConnectTimeout(CONNECT_TIMEOUT);
			conn.setReadTimeout(READ_TIMEOUT);
			conn.setRequestProperty("User-Agent", "jkite/" + Version.current());
			int status = conn.getResponseCode();
			if (status >= 300 && status < 400) {
				String location = conn.getHeaderField("Location");
				if (location == null) {
					throw new IOException("Redirect without Location from " + current);
				}
				current = resolve(current, location);
				conn.disconnect();
				JdkSource.requireRedirect(current, "to follow a redirect to");
				continue;
			}
			if (status < 200 || status >= 300) {
				throw new IOException("HTTP " + status + " when downloading " + current);
			}
			Files.createDirectories(target.toAbsolutePath().getParent());
			Path part = target.resolveSibling(target.getFileName() + ".part");
			try (InputStream is = conn.getInputStream()) {
				Files.copy(is, part, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				Util.deletePath(part, true);
				throw e;
			}
			Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
			return;
		}
		throw new IOException("Too many redirects for " + url);
	}

	/**
	 * The java.net.URL constructors are deprecated, so a URL is parsed as a URI
	 * and only turned into a URL to open the connection.
	 */
	private static URL toUrl(String url) throws IOException {
		try {
			return new URI(url).toURL();
		} catch (URISyntaxException | IllegalArgumentException e) {
			throw new IOException("Malformed URL: " + url, e);
		}
	}

	/** Resolves a Location header against the URL it was returned for. */
	private static String resolve(String base, String location) throws IOException {
		try {
			return new URI(base).resolve(new URI(location)).toString();
		} catch (URISyntaxException | IllegalArgumentException e) {
			throw new IOException("Malformed Location header from " + base + ": " + location, e);
		}
	}
}
