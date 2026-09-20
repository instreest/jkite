package io.github.instreest.jkite.jdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.instreest.jkite.Settings;

/**
 * The JVM index says where a JDK comes from and nothing in the project pins
 * what it says, so the one thing that keeps a bad index from choosing the
 * download is the check on where the URL points. The checksum cannot do it:
 * it is published beside the archive, so an index that picks the archive picks
 * the checksum too.
 */
class TestJdkManager {

	@TempDir
	Path jdksDir;

	private static JdkIndex.Entry entry(String url) {
		return new JdkIndex.Entry(Settings.JDK_DISTRO, "25.0.3", "tar.gz", url);
	}

	private static void requireSource(String url) throws IOException {
		new JdkManager().requireExpectedSource(entry(url));
	}

	private static IOException refused(String url) {
		return assertThrows(IOException.class, () -> requireSource(url));
	}

	@Test
	void theUrlTheIndexReallyPublishesIsAccepted() throws IOException {
		// as it stands in the index, percent-escaped tag and all
		requireSource("https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.3%2B9/"
				+ "OpenJDK25U-jdk_x64_linux_hotspot_25.0.3_9.tar.gz");
	}

	@Test
	void anyOtherHostIsRefused() {
		IOException e = refused("https://evil.example/adoptium/jdk.tar.gz");
		assertTrue(e.getMessage().contains("evil.example"), e.getMessage());
		assertTrue(e.getMessage().contains(Settings.JDK_DOWNLOAD_HOST), e.getMessage());
	}

	@Test
	void aLookalikeHostIsRefused() {
		refused("https://github.com.evil.example/adoptium/jdk.tar.gz");
	}

	@Test
	void aUrlWithNoHostIsRefused() {
		refused("file:///tmp/jdk.tar.gz");
	}

	/**
	 * The host is where anyone may publish, so it is not on its own a reason to
	 * download: these are all really on github.com.
	 */
	@Test
	void anotherAccountOnTheSameHostIsRefused() {
		IOException e = refused("https://github.com/attacker/temurin25-binaries/releases/download/x.tar.gz");
		assertTrue(e.getMessage().contains(Settings.JDK_DOWNLOAD_PATH_PREFIX), e.getMessage());
	}

	@Test
	void anAccountThatMerelyStartsLikeTheRealOneIsRefused() {
		refused("https://github.com/adoptium-evil/temurin25-binaries/releases/x.tar.gz");
	}

	@Test
	void theRealAccountFurtherDownThePathIsRefused() {
		refused("https://github.com/attacker/adoptium/releases/x.tar.gz");
	}

	@Test
	void theHostWithNoPathAtAllIsRefused() {
		refused("https://github.com");
	}

	/**
	 * The text before the @ in a URL is a user name, not a host, so a URL can
	 * read as the expected one and go somewhere else entirely.
	 */
	@Test
	void theHostSpelledAsAUserNameIsRefused() {
		refused("https://github.com@evil.example/adoptium/jdk.tar.gz");
	}

	/**
	 * %61 is the letter a once something decodes it. The check is made against
	 * the path as written, so a URL has to say where it goes plainly to be
	 * followed - this refuses a spelling that a reader of the decoded path
	 * would have taken for the real account.
	 */
	@Test
	void anEscapedSpellingOfTheAccountIsRefused() {
		refused("https://github.com/%61doptium/temurin25-binaries/releases/x.tar.gz");
	}

	@Test
	void somethingThatIsNotAUrlIsRefused() {
		refused("https://github.com/adoptium/ jdk .tar.gz");
	}

	/**
	 * A JDK carries where it was found, and --verbose prints it: a run that
	 * picked the wrong Java cannot be explained without it.
	 */
	@Test
	void aJdkFromTheCacheSaysWhereItCameFrom() throws IOException {
		fakeJdk("25.0.3", "25.0.3+9");

		List<Jdk> cached = new JdkManager(jdksDir, 17).listCachedJdks();

		assertEquals(1, cached.size());
		assertEquals(Jdk.Origin.CACHE, cached.get(0).origin());
		assertEquals("jkite", cached.get(0).origin().label(), "what --verbose prints for it");
	}

	@Test
	void theNewestOfTheCachedJdksComesFirst() throws IOException {
		fakeJdk("21.0.1", "21.0.1+12");
		fakeJdk("25.0.3", "25.0.3+9");

		List<String> found = new JdkManager(jdksDir, 17).listCachedJdks().stream()
			.map(Jdk::version)
			.collect(Collectors.toList());

		assertEquals(Arrays.asList("25.0.3+9", "21.0.1+12"), found);
	}

	/**
	 * Every place has a label of its own, and they are these. The labels are
	 * output, so a change to one is a change a reader sees; keeping them in one
	 * enum is what makes that a deliberate change rather than a missed rename.
	 */
	@Test
	void everyPlaceIsNamedAndNoTwoAreNamedAlike() {
		assertEquals("current", Jdk.Origin.CURRENT.label());
		assertEquals("JAVA_HOME", Jdk.Origin.JAVA_HOME.label());
		assertEquals("PATH", Jdk.Origin.PATH.label());
		assertEquals("jkite", Jdk.Origin.CACHE.label());

		Set<String> labels = Arrays.stream(Jdk.Origin.values())
			.map(Jdk.Origin::label)
			.collect(Collectors.toCollection(HashSet::new));
		assertEquals(Jdk.Origin.values().length, labels.size(), "two places with the same name explain nothing");
	}

	/** Enough of a JDK for Jdk.of: a javac to find and a release file to read. */
	private void fakeJdk(String dirName, String version) throws IOException {
		Path home = Files.createDirectories(jdksDir.resolve(dirName));
		Files.createDirectories(home.resolve("bin"));
		Files.write(home.resolve("bin").resolve("javac"), new byte[0]);
		Files.write(home.resolve("release"),
				("JAVA_VERSION=\"" + version + "\"\n").getBytes(StandardCharsets.UTF_8));
	}
}
