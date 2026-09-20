package io.github.instreest.jkite.jdk;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.jbang.ExitException;
import io.github.instreest.jkite.util.RequestedVersion;

/**
 * The JDK index as it is actually used: an excerpt of the Coursier index in the
 * shape it is published in, read and searched the way {@code JdkManager} does.
 */
class TestJdkIndex {

	/**
	 * Trimmed from io.get-coursier.jvm.indices:index-linux-amd64, keeping the
	 * shape: distribution to version to "&lt;archive type&gt;+&lt;url&gt;".
	 */
	private static final String INDEX = "{\n"
			+ "  \"temurin\": {\n"
			+ "    \"1.8.0-432\": \"tgz+https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u432-b06/OpenJDK8U-jdk_x64_linux_hotspot_8u432b06.tar.gz\",\n"
			+ "    \"17.0.13\": \"tgz+https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.13%2B11/OpenJDK17U-jdk_x64_linux_hotspot_17.0.13_11.tar.gz\",\n"
			+ "    \"21.0.5\": \"tgz+https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%2B11/OpenJDK21U-jdk_x64_linux_hotspot_21.0.5_11.tar.gz\",\n"
			+ "    \"25.0.3\": \"tgz+https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.3%2B9/OpenJDK25U-jdk_x64_linux_hotspot_25.0.3_9.tar.gz\"\n"
			+ "  },\n"
			+ "  \"graalvm-java17\": {\n"
			+ "    \"22.3.3\": \"tgz+https://github.com/graalvm/graalvm-ce-builds/releases/download/vm-22.3.3/graalvm-ce-java17-linux-amd64-22.3.3.tar.gz\"\n"
			+ "  },\n"
			+ "  \"zulu\": {\n"
			+ "    \"21.0.5\": \"zip+https://cdn.azul.com/zulu/bin/zulu21.38.21-ca-jdk21.0.5-linux_x64.zip\"\n"
			+ "  }\n"
			+ "}";

	private static JdkIndex index() {
		return JdkIndex.of("linux-amd64", INDEX);
	}

	@Test
	void findsTheNewestVersionSatisfyingAnOpenRequest() {
		Optional<JdkIndex.Entry> entry = index().find("temurin", RequestedVersion.parse("17+"));

		assertThat(entry.isPresent(), is(true));
		assertThat(entry.get().version, is("25.0.3"));
		assertThat(entry.get().distro, is("temurin"));
		assertThat(entry.get().archiveType, is("tgz"));
		assertThat(entry.get().url, containsString("OpenJDK25U-jdk_x64_linux_hotspot_25.0.3_9.tar.gz"));
	}

	@Test
	void findsAnExactMajorVersion() {
		JdkIndex.Entry entry = index().find("temurin", RequestedVersion.parse("17")).get();

		assertThat(entry.version, is("17.0.13"));
		assertThat(entry.url, containsString("17.0.13"));
	}

	@Test
	void readsTheArchiveTypeOfTheEntry() {
		JdkIndex.Entry entry = index().find("zulu", RequestedVersion.parse("21")).get();

		assertThat(entry.archiveType, is("zip"));
		assertThat(entry.url, containsString(".zip"));
	}

	@Test
	void aVersionNobodyPublishesIsNotFound() {
		assertThat(index().find("temurin", RequestedVersion.parse("99")).isPresent(), is(false));
	}

	@Test
	void anUnknownDistributionIsNotFound() {
		assertThat(index().find("nosuchdistro", RequestedVersion.parse("17")).isPresent(), is(false));
	}

	@Test
	void escapedUrlsSurviveParsingUnchanged() {
		// the index escapes the '+' of a JDK version as %2B; a parser that
		// unescaped or re-encoded it would break the download
		assertThat(index().find("temurin", RequestedVersion.parse("21")).get().url,
				containsString("jdk-21.0.5%2B11"));
	}

	@Test
	void entriesOfAnotherShapeAreSkippedRatherThanRejected() {
		// an index that grows entries we do not understand must not stop the
		// ones we do from being used
		JdkIndex index = JdkIndex.of("linux-amd64", "{\n"
				+ "  \"temurin\": { \"17.0.13\": \"tgz+https://example.org/jdk.tar.gz\",\n"
				+ "                 \"21.0.5\": { \"url\": \"https://example.org/other.tar.gz\" } },\n"
				+ "  \"weird\": \"not an object\",\n"
				+ "  \"empty\": {}\n"
				+ "}");

		assertThat(index.find("temurin", RequestedVersion.parse("17+")).get().version, is("17.0.13"));
		assertThat(index.find("weird", RequestedVersion.parse("17")).isPresent(), is(false));
		assertThat(index.find("empty", RequestedVersion.parse("17")).isPresent(), is(false));
	}

	@Test
	void anEntryWithoutAnArchiveTypeIsIgnored() {
		JdkIndex index = JdkIndex.of("linux-amd64",
				"{ \"temurin\": { \"17.0.13\": \"https://example.org/jdk.tar.gz\" } }");

		assertThat(index.find("temurin", RequestedVersion.parse("17")).isPresent(), is(false));
	}

	@Test
	void indexThatIsNotJsonIsReported() {
		ExitException e = assertThrows(ExitException.class,
				() -> JdkIndex.of("linux-amd64", "<html>404</html>"));
		assertThat(e.getMessage(), containsString("not valid"));
	}

	@Test
	void indexThatIsNotAnObjectIsReported() {
		ExitException e = assertThrows(ExitException.class, () -> JdkIndex.of("linux-amd64", "[1, 2, 3]"));
		assertThat(e.getMessage(), containsString("not valid"));
	}
}
