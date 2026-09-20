package io.github.instreest.jkite.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import org.junit.jupiter.api.Test;

/**
 * Whether a jar built earlier can run again. Getting this wrong is expensive in
 * both directions: rebuild every time and jkite is slow, reuse a jar the JDK
 * of the day cannot load and the script does not start at all.
 */
class TestJarReuse {

	/** Java 21 is here, and counts how often it was asked. */
	private final AtomicInteger asked = new AtomicInteger();
	private final IntSupplier here = () -> {
		asked.incrementAndGet();
		return 21;
	};

	@Test
	void aJarThatSaysNothingAboutItsJdkIsNotReused() {
		String reason = AppBuilder.cannotReuse(null, "17+", here, false);

		assertNotNull(reason);
		assertTrue(reason.contains("incomplete meta data"), reason);
	}

	@Test
	void aJarBuiltWithTheJdkOfTheDayIsReused() {
		assertNull(AppBuilder.cannotReuse("21.0.12+7", "17+", here, false));
	}

	@Test
	void aJarTooOldForWhatTheScriptAsksIsNotReused() {
		String reason = AppBuilder.cannotReuse("17.0.9+9", "21+", here, false);

		assertNotNull(reason);
		assertTrue(reason.contains("does not satisfy the requested version 21+"), reason);
	}

	/**
	 * Finding out which JDK is here can mean downloading one, and that is not
	 * worth doing to decide something the version already decided.
	 */
	@Test
	void theJdkIsNotLookedUpWhenTheVersionAlreadySettlesIt() {
		AppBuilder.cannotReuse("17.0.9+9", "21+", here, false);

		assertEquals(0, asked.get(), "the JDK was looked up when it did not have to be");
	}

	@Test
	void aJarBuiltWithANewerJdkThanIsHereIsNotReused() {
		String reason = AppBuilder.cannotReuse("25.0.3+9", null, here, false);

		assertNotNull(reason);
		assertTrue(reason.contains("newer than the JDK available now"), reason);
	}

	@Test
	void aPreviewJarIsReusedOnlyByTheJdkThatBuiltIt() {
		assertNull(AppBuilder.cannotReuse("21.0.12+7", "17+", here, true));
	}

	/**
	 * A class file that uses preview features is loadable only by the JVM of
	 * exactly its own version, so here a newer JDK is not an upgrade.
	 */
	@Test
	void aPreviewJarFromAnOlderJdkIsNotReused() {
		String reason = AppBuilder.cannotReuse("17.0.9+9", "17+", here, true);

		assertNotNull(reason);
		assertTrue(reason.contains("preview features of Java 17"), reason);
		assertTrue(reason.contains("Java 21 does not load"), reason);
	}
}
