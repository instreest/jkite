package io.github.instreest.jkite.jdk;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.jbang.ExitException;
import dev.jbang.util.Util;

/**
 * Alpine, where the two halves of a run used to disagree.
 *
 * jkite-bootstrap-jdk has always matched /etc/alpine-release and refused by
 * name, and README says a JDK has to be installed by hand there. The jar only
 * warned and carried on, so a run ended after several minutes and two hundred
 * megabytes, at the exec of a glibc binary on a musl machine, rather than at
 * the decision.
 */
class TestAlpineRefusal {

	@Test
	void onAlpineThereIsNothingToInstall() {
		ExitException e = assertThrows(ExitException.class,
				() -> JdkManager.requireAnInstallableJdk(Util.OS.alpine_linux, null));

		assertTrue(e.getMessage().contains("Alpine"), e.getMessage());
		assertTrue(e.getMessage().contains("JAVA_HOME"), "it should say what to do instead: " + e.getMessage());
		assertTrue(e.getMessage().contains("JKITE_JDK_INDEX"), e.getMessage());
	}

	/**
	 * An index of musl builds is the caller's to supply, and having supplied
	 * one they are not to be told it cannot be done. This is the way out the
	 * old warning pointed at, so refusing here would have closed it.
	 */
	@Test
	void anIndexOfItsOwnIsTheWayOut() {
		JdkManager.requireAnInstallableJdk(Util.OS.alpine_linux, "/opt/musl-index.json");
	}

	@Test
	void anEmptyOverrideIsNotAnIndex() {
		assertThrows(ExitException.class,
				() -> JdkManager.requireAnInstallableJdk(Util.OS.alpine_linux, "   "));
	}

	@Test
	void everywhereElseIsUnaffected() {
		JdkManager.requireAnInstallableJdk(Util.OS.linux, null);
		JdkManager.requireAnInstallableJdk(Util.OS.mac, null);
		JdkManager.requireAnInstallableJdk(Util.OS.windows, null);
	}
}
