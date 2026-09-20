package io.github.instreest.jkite.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import dev.jbang.util.Util;

/**
 * Teaches the JVM the proxy the launcher was already using.
 *
 * The launcher fetches the jar and a JDK with curl or wget, and both read
 * http_proxy, https_proxy and no_proxy out of the environment. Java reads none
 * of the three - it wants -Dhttps.proxyHost and friends - so on a machine
 * behind a proxy the first half of a run goes through it and the second half
 * tries to go direct and hangs or is refused. jkite exists so that nobody has
 * to think about how the tool gets what it needs; two different answers to
 * "which proxy" inside one run is the opposite of that.
 *
 * So the variables are translated into the properties the JVM does read, once,
 * before anything connects. Nothing already set is overwritten: a
 * JKITE_JAVA_OPTIONS=-Dhttps.proxyHost=... is somebody being explicit, and
 * being explicit wins.
 *
 * This covers what jkite downloads itself - the JVM index and the JDK, which
 * go through HttpURLConnection. Dependencies are fetched by Maven Resolver,
 * which is configured the way Maven is configured, in the &lt;proxies&gt;
 * section of ~/.m2/settings.xml.
 */
public final class EnvironmentProxy {

	private EnvironmentProxy() {
	}

	/** Reads the real environment and sets the real system properties. */
	public static void apply() {
		apply(System.getenv(), System::getProperty, Util::warnMsg, System::setProperty);
	}

	/**
	 * @param env       the environment to read
	 * @param current   what a system property is now, or null
	 * @param onWarning told about a value that could not be read
	 * @param set       how to set a system property
	 */
	static void apply(Map<String, String> env, Function<String, String> current,
			Consumer<String> onWarning, BiConsumer<String, String> set) {
		for (String scheme : Arrays.asList("http", "https")) {
			String value = firstOf(env, scheme + "_proxy", scheme.toUpperCase(Locale.ROOT) + "_PROXY");
			if (value == null) {
				continue;
			}
			// already answered, by JKITE_JAVA_OPTIONS or by the embedding JVM
			if (current.apply(scheme + ".proxyHost") != null) {
				continue;
			}
			URI uri = parse(value);
			if (uri == null || uri.getHost() == null) {
				onWarning.accept("Ignoring " + scheme + "_proxy, which is not a URL: " + value);
				continue;
			}
			set.accept(scheme + ".proxyHost", uri.getHost());
			set.accept(scheme + ".proxyPort", Integer.toString(port(uri)));
		}
		String no = firstOf(env, "no_proxy", "NO_PROXY");
		if (no != null && current.apply("http.nonProxyHosts") == null) {
			String hosts = nonProxyHosts(no);
			if (!hosts.isEmpty()) {
				// one property covers both schemes; the JDK reads
				// http.nonProxyHosts for https as well
				set.accept("http.nonProxyHosts", hosts);
			}
		}
	}

	private static String firstOf(Map<String, String> env, String... names) {
		for (String name : names) {
			String v = env.get(name);
			if (v != null && !v.trim().isEmpty()) {
				return v.trim();
			}
		}
		return null;
	}

	/**
	 * curl accepts a proxy with no scheme ("proxy.corp:3128"), which URI reads
	 * as a scheme of "proxy.corp" and no host, so one is supplied.
	 */
	static URI parse(String value) {
		try {
			URI uri = new URI(value.contains("://") ? value : "http://" + value);
			return uri.getHost() != null ? uri : null;
		} catch (URISyntaxException e) {
			return null;
		}
	}

	static int port(URI uri) {
		if (uri.getPort() != -1) {
			return uri.getPort();
		}
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

	/**
	 * no_proxy is a comma-separated list where a leading dot, or no dot at all,
	 * means "and anything under it". http.nonProxyHosts is a pipe-separated
	 * list of patterns where only a leading "*" means that, so ".corp.example"
	 * has to become "*.corp.example" and be kept alongside "corp.example",
	 * which the star does not match.
	 */
	static String nonProxyHosts(String noProxy) {
		List<String> out = new ArrayList<>();
		for (String raw : noProxy.split(",")) {
			String entry = raw.trim();
			if (entry.isEmpty()) {
				continue;
			}
			// curl's "everything" wildcard; there is no equivalent pattern, and
			// the honest translation is to leave the proxy unset altogether,
			// which the caller does by finding no hosts worth listing
			if ("*".equals(entry)) {
				return "*";
			}
			// a port in a no_proxy entry has no counterpart in nonProxyHosts,
			// which matches on the host alone
			int colon = entry.lastIndexOf(':');
			if (colon > 0 && entry.indexOf(':') == colon && entry.substring(colon + 1).matches("\\d+")) {
				entry = entry.substring(0, colon);
			}
			if (entry.startsWith(".")) {
				add(out, "*" + entry);
				add(out, entry.substring(1));
			} else {
				add(out, entry);
				add(out, "*." + entry);
			}
		}
		return String.join("|", out);
	}

	private static void add(List<String> out, String entry) {
		if (!entry.isEmpty() && !out.contains(entry)) {
			out.add(entry);
		}
	}
}
