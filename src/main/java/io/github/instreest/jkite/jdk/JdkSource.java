package io.github.instreest.jkite.jdk;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import io.github.instreest.jkite.Settings;

/**
 * Decides whether a URL may be fetched on the JDK path. It is the only thing
 * standing between a bad JVM index and an arbitrary download: the index says
 * where a JDK comes from, it is the one input here that nothing in the project
 * pins, and the checksum cannot help, since it is published beside the archive
 * and so is chosen by whoever chose the archive.
 *
 * Two questions, because they are not the same question. The URL the index
 * gives is checked strictly: it must name the distribution's own account, and
 * nothing about it may be open to interpretation. Where GitHub then redirects
 * that request is not the index's choice, so a redirect is only held to its
 * host.
 */
final class JdkSource {

	private JdkSource() {
	}

	/**
	 * Refuses anything but an archive published by the distribution itself.
	 *
	 * The account is checked and not only the host: anyone can publish a release
	 * on github.com, so the host alone would let every one of them through.
	 *
	 * Everything is read from a parsed URL rather than matched against its text,
	 * and every way of writing a path that means something other than it appears
	 * to is refused outright rather than resolved:
	 * <ul>
	 * <li>"https://github.com&#64;evil.example/adoptium/" has the text but not
	 * the host, so the host comes from parsing.
	 * <li>"https://github.com/adoptium/../../attacker/x.tar.gz" has the prefix,
	 * but a server resolves the dot segments and answers from somewhere else.
	 * Java sends the path unnormalised, so this has to be refused here.
	 * <li>"%2e%2e" and "%2f" are those same segments spelt so that only the
	 * server decodes them, and ";" starts a path parameter some servers strip.
	 * </ul>
	 * A query or a fragment is refused for the same reason: neither belongs on a
	 * release asset, and both are a way to carry something past a check that
	 * only reads the path.
	 */
	static void requireIndexUrl(String url, String context) throws IOException {
		URI uri = parse(url, context);
		requireHttps(uri, url, context);
		if (uri.getRawUserInfo() != null) {
			throw refuse(url, context, "it carries a user name, so the host is not what it appears to be");
		}
		if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
			throw refuse(url, context, "a release asset has no query or fragment");
		}
		String host = uri.getHost();
		if (host == null || !host.equalsIgnoreCase(Settings.JDK_DOWNLOAD_HOST)) {
			throw refuse(url, context, "JDKs are only downloaded from https://"
					+ Settings.JDK_DOWNLOAD_HOST + Settings.JDK_DOWNLOAD_PATH_PREFIX);
		}
		String path = uri.getRawPath();
		if (path == null) {
			throw refuse(url, context, "it names no path");
		}
		String lower = path.toLowerCase(java.util.Locale.ROOT);
		if (lower.contains("%2e") || lower.contains("%2f") || path.indexOf(';') >= 0) {
			throw refuse(url, context, "its path is escaped in a way that only the server would resolve");
		}
		for (String segment : path.split("/", -1)) {
			if (segment.equals(".") || segment.equals("..")) {
				throw refuse(url, context, "its path walks with '.' or '..', which the server would resolve"
						+ " to somewhere other than where the path appears to lead");
			}
		}
		if (!path.startsWith(Settings.JDK_DOWNLOAD_PATH_PREFIX)) {
			throw refuse(url, context, "JDKs are only downloaded from https://"
					+ Settings.JDK_DOWNLOAD_HOST + Settings.JDK_DOWNLOAD_PATH_PREFIX);
		}
	}

	/**
	 * Refuses a redirect that leaves GitHub. A release asset on github.com is
	 * answered by a redirect to a content host whose name GitHub has changed
	 * before and will change again - it is release-assets.githubusercontent.com
	 * today and was objects.githubusercontent.com - so the whole of
	 * githubusercontent.com is allowed rather than one name that would turn a
	 * GitHub change into a jkite outage.
	 *
	 * The path is not checked, and neither is the query: the signed URL these
	 * redirects carry is GitHub's to shape, and this hop was chosen by GitHub
	 * rather than by the index.
	 */
	static void requireRedirect(String url, String context) throws IOException {
		URI uri = parse(url, context);
		requireHttps(uri, url, context);
		if (uri.getRawUserInfo() != null) {
			throw refuse(url, context, "a redirect carrying a user name is not one of GitHub's");
		}
		String host = uri.getHost();
		if (host == null) {
			throw refuse(url, context, "it names no host");
		}
		host = host.toLowerCase(java.util.Locale.ROOT);
		if (!host.equals(Settings.JDK_DOWNLOAD_HOST) && !host.endsWith(Settings.JDK_REDIRECT_HOST_SUFFIX)) {
			throw refuse(url, context, "a JDK download is only followed within "
					+ Settings.JDK_DOWNLOAD_HOST + " and" + Settings.JDK_REDIRECT_HOST_SUFFIX);
		}
	}

	private static URI parse(String url, String context) throws IOException {
		try {
			return new URI(url);
		} catch (URISyntaxException | IllegalArgumentException e) {
			throw refuse(url, context, "it is not a URL");
		}
	}

	private static void requireHttps(URI uri, String url, String context) throws IOException {
		if (!"https".equalsIgnoreCase(String.valueOf(uri.getScheme()))) {
			throw refuse(url, context, "only https is followed, so that nothing can be read or rewritten"
					+ " on the way");
		}
	}

	private static IOException refuse(String url, String context, String why) {
		return new IOException("Refusing " + context + " " + url + ": " + why);
	}
}
