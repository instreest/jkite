package io.github.instreest.jkite.jdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

/**
 * How much of the JDK index jkite is willing to read.
 *
 * The index is a file this project does not publish, fetched from Maven
 * Central at an open version range, and a zip entry says how long it is
 * without having to be telling the truth. Reading it until it stops is
 * therefore reading it until the JVM stops. The real index for a platform is
 * around 360 kB and compresses about fifteen to one, so a limit some forty
 * times that is nothing to a real one and everything to a stream that does
 * not end.
 */
class TestIndexSize {

	@Test
	void anIndexOfTheUsualSizeIsRead() throws IOException {
		String json = "{\"temurin\":{\"25.0.3\":\"tgz+https://example.invalid/jdk.tar.gz\"}}";

		assertEquals(json, JdkIndex.readAtMost(stream(json), JdkIndex.MAX_INDEX_BYTES, Paths.get("x.jar")));
	}

	@Test
	void theLimitIsFarAboveARealIndex() {
		// 360 kB today; this is the headroom, stated so that shrinking the
		// limit to something a real index would hit fails here first
		assertTrue(JdkIndex.MAX_INDEX_BYTES > 40 * 360 * 1024,
				"the limit is no longer far above a real index: " + JdkIndex.MAX_INDEX_BYTES);
	}

	@Test
	void oneThatDoesNotStopIsRefused() {
		IOException e = assertThrows(IOException.class,
				() -> JdkIndex.readAtMost(endless(), 64 * 1024, Paths.get("endless.jar")));

		assertTrue(e.getMessage().contains("endless.jar"), e.getMessage());
		assertTrue(e.getMessage().contains("longer than"), e.getMessage());
	}

	/** Exactly at the limit is still an index; one byte past it is not. */
	@Test
	void theLimitIsWhereItSays() throws IOException {
		byte[] full = new byte[100];
		java.util.Arrays.fill(full, (byte) 'a');

		assertEquals(100, JdkIndex.readAtMost(
				new ByteArrayInputStream(full), 100, Paths.get("x.jar")).length());
		assertThrows(IOException.class,
				() -> JdkIndex.readAtMost(new ByteArrayInputStream(full), 99, Paths.get("x.jar")));
	}

	private static InputStream stream(String s) {
		return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
	}

	/** A zip entry that says it is short and then is not. */
	private static InputStream endless() {
		return new InputStream() {
			@Override
			public int read() {
				return 'a';
			}

			@Override
			public int read(byte[] b, int off, int len) {
				java.util.Arrays.fill(b, off, off + len, (byte) 'a');
				return len;
			}
		};
	}
}
