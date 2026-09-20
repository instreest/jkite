package io.github.instreest.jkite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

/**
 * Where the dependency jars live, which is the difference between a run being
 * the project's and being the machine's.
 *
 * Maven Resolver's own default is ~/.m2/repository, and taking it made two of
 * jkite's claims untrue. "Everything jkite writes goes under JKITE_DIR" was
 * not, and emptying JKITE_DIR did not give a cold machine, because the jars
 * that actually reach the class path were still in ~/.m2 - where, since the
 * JVM takes user.home from the passwd entry and not from HOME, a test could
 * not even move them out of the way. Whatever another build had done to that
 * directory decided what a jkite run compiled against.
 */
class TestSettingsLocalRepo {

	@Test
	void theLocalRepositoryIsUnderTheCacheDirectory() {
		Path repo = Settings.localMavenRepo(null);

		assertTrue(repo.startsWith(Settings.getCacheDir()),
				"the dependency jars are kept outside JKITE_DIR: " + repo);
		assertEquals("deps", repo.getFileName().toString(), repo.toString());
	}

	/**
	 * Not ~/.m2/repository, said as its own assertion because that is the
	 * default this exists to refuse - and an accident that put it back would
	 * otherwise only show as a slow first run.
	 */
	@Test
	void itIsNotTheUsersOwnMavenRepository() {
		Path repo = Settings.localMavenRepo(null);

		assertTrue(!repo.toString().contains(".m2"),
				"it fell back to the machine's Maven repository: " + repo);
	}

	/** And a machine that would rather share ~/.m2 still can. */
	@Test
	void jkiteMavenRepoStillDecidesWhenItIsSet() {
		assertEquals(Paths.get("/somewhere/else"), Settings.localMavenRepo("/somewhere/else"));
	}
}
