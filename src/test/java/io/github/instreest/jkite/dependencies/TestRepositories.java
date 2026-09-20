package io.github.instreest.jkite.dependencies;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.jbang.ExitException;
import dev.jbang.dependencies.MavenRepo;

/**
 * Where //DEPS may be resolved from. A dependency is the one thing jkite
 * downloads that becomes code running on the machine, so the repository a
 * script names is held to the same rule as everything else jkite fetches.
 */
class TestRepositories {

	private static ExitException refused(String url) {
		return assertThrows(ExitException.class,
				() -> DependencyResolver.requireSafeRepository(new MavenRepo("r", url)));
	}

	@Test
	void httpsIsAccepted() {
		DependencyResolver.requireSafeRepository(new MavenRepo("central", "https://repo1.maven.org/maven2"));
	}

	/** An air-gapped machine resolving from a directory touches no network. */
	@Test
	void aFilePathIsAccepted() {
		DependencyResolver.requireSafeRepository(new MavenRepo("mirror", "file:///opt/maven-mirror"));
	}

	/** How a test serves a repository to itself. */
	@Test
	void plainHttpOnTheLoopbackAddressIsAccepted() {
		DependencyResolver.requireSafeRepository(new MavenRepo("local", "http://127.0.0.1:8081/repo"));
		DependencyResolver.requireSafeRepository(new MavenRepo("local", "http://localhost:8081/repo"));
	}

	@Test
	void plainHttpIsRefused() {
		ExitException e = refused("http://repo.example.com/maven2");
		assertTrue(e.getMessage().contains("repo.example.com"), e.getMessage());
		assertTrue(e.getMessage().contains("https"), e.getMessage());
	}

	/**
	 * The loopback exception is for the address, not for the name: a host that
	 * merely begins with it is somewhere else entirely.
	 */
	@Test
	void aHostThatOnlyBeginsLikeTheLoopbackIsRefused() {
		refused("http://127.0.0.1.evil.example/repo");
		refused("http://localhost.evil.example/repo");
	}

	/**
	 * The scheme is where the URL begins, not somewhere inside it: this one is
	 * fetched over http however much of an https URL it carries in its query.
	 */
	@Test
	void anHttpUrlThatMentionsHttpsIsStillRefused() {
		ExitException e = refused("http://evil.example/proxy?to=https://repo1.maven.org/maven2");
		assertTrue(e.getMessage().contains("evil.example"), e.getMessage());
	}

	@Test
	void anotherSchemeIsRefused() {
		refused("ftp://repo.example.com/maven2");
	}

	@Test
	void aUrlWithNoSchemeIsRefused() {
		refused("repo.example.com/maven2");
	}

	@Test
	void anEmptyUrlIsRefused() {
		refused("");
	}
}
