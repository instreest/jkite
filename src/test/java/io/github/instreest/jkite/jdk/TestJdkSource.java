package io.github.instreest.jkite.jdk;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * The JVM index says where a JDK comes from and nothing in the project pins
 * what it says, so this policy is the whole defence. The checksum cannot help:
 * it is published beside the archive, so an index that chose the one chose the
 * other.
 */
class TestJdkSource {

	private static final String REAL = "https://github.com/adoptium/temurin25-binaries/releases/download/"
			+ "jdk-25.0.3%2B9/OpenJDK25U-jdk_x64_linux_hotspot_25.0.3_9.tar.gz";

	private static IOException refusedIndex(String url) {
		return assertThrows(IOException.class, () -> JdkSource.requireIndexUrl(url, "to download"));
	}

	private static IOException refusedRedirect(String url) {
		return assertThrows(IOException.class, () -> JdkSource.requireRedirect(url, "to follow a redirect to"));
	}

	// ------------------------------------------------ the URL the index gives

	@Test
	void theUrlTheIndexReallyPublishesIsAccepted() throws IOException {
		// exactly as it stands in the index, percent-escaped tag and all
		JdkSource.requireIndexUrl(REAL, "to download");
	}

	@Test
	void theChecksumBesideItIsAccepted() throws IOException {
		JdkSource.requireIndexUrl(REAL + ".sha256.txt", "to download");
	}

	@Test
	void anotherHostIsRefused() {
		IOException e = refusedIndex("https://evil.example/adoptium/jdk.tar.gz");
		assertTrue(e.getMessage().contains("github.com"), e.getMessage());
	}

	@Test
	void aHostThatMerelyEndsLikeTheRealOneIsRefused() {
		refusedIndex("https://github.com.evil.example/adoptium/jdk.tar.gz");
	}

	/** The text before the @ is a user name, so this really goes to evil.example. */
	@Test
	void theHostSpelledAsAUserNameIsRefused() {
		refusedIndex("https://github.com@evil.example/adoptium/jdk.tar.gz");
	}

	/**
	 * Here the host really is github.com and the path really is the account, so
	 * nothing else in the check objects - only the rule that a release asset
	 * carries no credentials does. Without it, a URL from the index could make
	 * jkite send whatever it named to a server as a user name and password.
	 */
	@Test
	void credentialsOnTheRightHostAreRefusedToo() {
		IOException e = refusedIndex("https://user:pw@github.com/adoptium/temurin25-binaries/releases/x.tar.gz");
		assertTrue(e.getMessage().contains("user name"), e.getMessage());
	}

	@Test
	void anotherAccountOnTheSameHostIsRefused() {
		IOException e = refusedIndex("https://github.com/attacker/temurin25-binaries/releases/x.tar.gz");
		assertTrue(e.getMessage().contains("/adoptium/"), e.getMessage());
	}

	@Test
	void anAccountThatMerelyStartsLikeTheRealOneIsRefused() {
		refusedIndex("https://github.com/adoptium-evil/releases/x.tar.gz");
	}

	@Test
	void theRealAccountFurtherDownThePathIsRefused() {
		refusedIndex("https://github.com/attacker/adoptium/releases/x.tar.gz");
	}

	/**
	 * The prefix is there, but a server resolves the dot segments and answers
	 * from another account. Java sends the path unnormalised, so believing the
	 * text here would mean downloading from wherever it led.
	 */
	@Test
	void aPathThatWalksOutOfTheAccountIsRefused() {
		IOException e = refusedIndex("https://github.com/adoptium/../../attacker/evil.tar.gz");
		assertTrue(e.getMessage().contains("'..'"), e.getMessage());
	}

	@Test
	void aSingleDotSegmentIsRefusedToo() {
		refusedIndex("https://github.com/adoptium/./../attacker/evil.tar.gz");
	}

	/** %2e%2e is ".." spelt so that only the server decodes it. */
	@Test
	void anEscapedWalkIsRefused() {
		IOException e = refusedIndex("https://github.com/adoptium/%2e%2e/%2e%2e/attacker/evil.tar.gz");
		assertTrue(e.getMessage().contains("escaped"), e.getMessage());
	}

	@Test
	void anEscapedSlashIsRefused() {
		refusedIndex("https://github.com/adoptium%2F..%2Fattacker/evil.tar.gz");
	}

	/** An escaped spelling of the account itself, which only a decoder reads as "adoptium". */
	@Test
	void anEscapedSpellingOfTheAccountIsRefused() {
		refusedIndex("https://github.com/%61doptium/releases/x.tar.gz");
	}

	/** Some servers strip a path parameter, so what they serve is not what was checked. */
	@Test
	void aPathParameterIsRefused() {
		refusedIndex("https://github.com/adoptium;x=../attacker/evil.tar.gz");
	}

	@Test
	void aQueryIsRefused() {
		IOException e = refusedIndex(REAL + "?redirect=https://evil.example/");
		assertTrue(e.getMessage().contains("query"), e.getMessage());
	}

	@Test
	void aFragmentIsRefused() {
		refusedIndex(REAL + "#evil");
	}

	@Test
	void plainHttpIsRefused() {
		IOException e = refusedIndex("http://github.com/adoptium/releases/x.tar.gz");
		assertTrue(e.getMessage().contains("https"), e.getMessage());
	}

	@Test
	void aLocalFileIsRefused() {
		refusedIndex("file:///tmp/jdk.tar.gz");
	}

	@Test
	void theHostWithNoPathAtAllIsRefused() {
		refusedIndex("https://github.com");
	}

	@Test
	void somethingThatIsNotAUrlIsRefused() {
		IOException e = refusedIndex("https://github.com/adoptium/ jdk .tar.gz");
		assertTrue(e.getMessage().contains("not a URL"), e.getMessage());
	}

	// ------------------------------------------------------------- redirects

	/**
	 * What github.com really answers with for a release asset: another host,
	 * carrying a signed query. GitHub chose this hop, not the index, so the
	 * path and the query are its business.
	 */
	@Test
	void theContentHostGitHubRedirectsToIsFollowed() throws IOException {
		JdkSource.requireRedirect("https://release-assets.githubusercontent.com/github-production-release-asset/"
				+ "901810329/4398bf43?sp=r&sig=abc%3D&response-content-type=application%2Foctet-stream",
				"to follow a redirect to");
	}

	/** The name GitHub used before this one; both must keep working. */
	@Test
	void theContentHostGitHubUsedBeforeIsFollowedToo() throws IOException {
		JdkSource.requireRedirect("https://objects.githubusercontent.com/x/y?sig=abc", "to follow a redirect to");
	}

	@Test
	void aRedirectBackToGitHubIsFollowed() throws IOException {
		JdkSource.requireRedirect(REAL, "to follow a redirect to");
	}

	@Test
	void aRedirectOffGitHubIsRefused() {
		IOException e = refusedRedirect("https://evil.example/jdk.tar.gz");
		assertTrue(e.getMessage().contains("github.com"), e.getMessage());
	}

	/** The suffix has to match on a dot, or "evilgithubusercontent.com" would pass. */
	@Test
	void aHostThatOnlyEndsWithTheSuffixTextIsRefused() {
		refusedRedirect("https://evilgithubusercontent.com/x/y");
	}

	@Test
	void aRedirectOntoPlainHttpIsRefused() {
		IOException e = refusedRedirect("http://objects.githubusercontent.com/x/y");
		assertTrue(e.getMessage().contains("https"), e.getMessage());
	}

	@Test
	void aRedirectCarryingAUserNameIsRefused() {
		refusedRedirect("https://objects.githubusercontent.com@evil.example/x/y");
	}

	/** As above: an allowed host, refused only because it asks for credentials. */
	@Test
	void credentialsOnAnAllowedRedirectHostAreRefusedToo() {
		IOException e = refusedRedirect("https://user:pw@objects.githubusercontent.com/x/y");
		assertTrue(e.getMessage().contains("user name"), e.getMessage());
	}
}
