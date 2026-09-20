package io.github.instreest.jkite.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Translating the proxy the launcher used into the one the JVM will use.
 *
 * Verified first that there is something to translate: a JVM with
 * https_proxy set in its environment and no -D selects DIRECT for an https
 * URL, while curl in the same environment uses the proxy. That is the two
 * halves of one run disagreeing.
 */
class TestEnvironmentProxy {

	private final Map<String, String> set = new LinkedHashMap<>();
	private final List<String> warnings = new ArrayList<>();
	private final Map<String, String> already = new HashMap<>();

	private void apply(Map<String, String> env) {
		EnvironmentProxy.apply(env, already::get, warnings::add, set::put);
	}

	private static Map<String, String> env(String... pairs) {
		Map<String, String> env = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			env.put(pairs[i], pairs[i + 1]);
		}
		return env;
	}

	@Test
	void aProxyUrlBecomesHostAndPort() {
		apply(env("https_proxy", "http://proxy.corp:3128"));

		assertEquals("proxy.corp", set.get("https.proxyHost"));
		assertEquals("3128", set.get("https.proxyPort"));
	}

	@Test
	void theUppercaseSpellingWorksToo() {
		apply(env("HTTPS_PROXY", "http://proxy.corp:3128"));

		assertEquals("proxy.corp", set.get("https.proxyHost"));
	}

	/** curl takes a proxy with no scheme, so a URI parse alone is not enough. */
	@Test
	void aProxyWithNoSchemeIsUnderstood() {
		apply(env("http_proxy", "proxy.corp:3128"));

		assertEquals("proxy.corp", set.get("http.proxyHost"));
		assertEquals("3128", set.get("http.proxyPort"));
	}

	@Test
	void aProxyWithNoPortGetsTheSchemeDefault() {
		apply(env("http_proxy", "http://proxy.corp", "https_proxy", "https://secure.corp"));

		assertEquals("80", set.get("http.proxyPort"));
		assertEquals("443", set.get("https.proxyPort"));
	}

	/**
	 * JKITE_JAVA_OPTIONS=-Dhttps.proxyHost=... is somebody saying exactly what
	 * they want, and it has to beat a guess made from the environment.
	 */
	@Test
	void anExplicitPropertyIsNotOverwritten() {
		already.put("https.proxyHost", "chosen.example");

		apply(env("https_proxy", "http://proxy.corp:3128"));

		assertNull(set.get("https.proxyHost"), "the -D was overwritten: " + set);
		assertNull(set.get("https.proxyPort"), "and its port with it: " + set);
	}

	@Test
	void nothingInTheEnvironmentSetsNothing() {
		apply(env());

		assertTrue(set.isEmpty(), set.toString());
	}

	@Test
	void anUnreadableValueIsReportedRatherThanGuessedAt() {
		apply(env("https_proxy", "::::"));

		assertTrue(set.isEmpty(), set.toString());
		assertEquals(1, warnings.size(), warnings.toString());
		assertTrue(warnings.get(0).contains("https_proxy"), warnings.get(0));
	}

	// -------------------------------------------------------------------------
	// no_proxy, which is where the two formats really differ
	// -------------------------------------------------------------------------

	/**
	 * no_proxy matches suffixes: "corp.example" covers "a.corp.example".
	 * nonProxyHosts matches patterns, and "corp.example" covers only itself,
	 * so both spellings have to be listed.
	 */
	@Test
	void aBareNameCoversItselfAndWhatIsUnderIt() {
		assertEquals(Arrays.asList("corp.example", "*.corp.example"),
				Arrays.asList(EnvironmentProxy.nonProxyHosts("corp.example").split("\\|")));
	}

	/** A leading dot means the same thing, written the other way round. */
	@Test
	void aLeadingDotCoversTheBareNameToo() {
		assertEquals(Arrays.asList("*.corp.example", "corp.example"),
				Arrays.asList(EnvironmentProxy.nonProxyHosts(".corp.example").split("\\|")));
	}

	@Test
	void severalEntriesAreSeparatedByPipes() {
		assertEquals("localhost|*.localhost|127.0.0.1|*.127.0.0.1",
				EnvironmentProxy.nonProxyHosts("localhost, 127.0.0.1"));
	}

	/** nonProxyHosts matches the host alone, so a port in no_proxy is dropped. */
	@Test
	void aPortIsDropped() {
		assertEquals("corp.example|*.corp.example",
				EnvironmentProxy.nonProxyHosts("corp.example:8080"));
	}

	/**
	 * An IPv6 literal has colons of its own and must not be cut at the last
	 * one. Written bare rather than in brackets on purpose: "[::1]" survives a
	 * naive port-strip anyway, because what follows the last colon is "1]" and
	 * not a number, so it would not tell a correct implementation from one
	 * that cuts at any colon. "::1" does - a naive strip leaves ":".
	 */
	@Test
	void anIpv6LiteralKeepsItsColons() {
		assertEquals("::1|*.::1", EnvironmentProxy.nonProxyHosts("::1"));
		assertTrue(EnvironmentProxy.nonProxyHosts("[::1]").contains("[::1]"),
				EnvironmentProxy.nonProxyHosts("[::1]"));
	}

	@Test
	void theEverythingWildcardStaysAWildcard() {
		assertEquals("*", EnvironmentProxy.nonProxyHosts("*"));
	}

	@Test
	void noProxyReachesTheProperty() {
		apply(env("https_proxy", "http://proxy.corp:3128", "no_proxy", "corp.example"));

		assertEquals("corp.example|*.corp.example", set.get("http.nonProxyHosts"));
	}

	/**
	 * One property covers both schemes - the JDK reads http.nonProxyHosts for
	 * https requests as well - so there is no https.nonProxyHosts to set.
	 */
	@Test
	void thereIsOnlyOneNonProxyHostsProperty() {
		apply(env("https_proxy", "http://proxy.corp:3128", "no_proxy", "corp.example"));

		assertNull(set.get("https.nonProxyHosts"), set.toString());
	}
}
