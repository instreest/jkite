package io.github.instreest.jkite;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Enumeration;
import java.util.jar.Manifest;

/**
 * The version of the jkite that is running, as <code>--version</code> reports
 * it and as the User-Agent of every download names it.
 *
 * It is written into jkite.jar's manifest at build time, twice. As
 * <code>Implementation-Version</code>, which is the attribute Java itself reads
 * back through {@link Package}, and as {@link #ATTRIBUTE}, which marks a
 * manifest as this project's own.
 *
 * {@link Package} is asked first because it answers with the jar this class was
 * loaded from. Reading a manifest by hand does not: a class path holds many
 * manifests and the first is whichever jar comes first, which is why the search
 * below looks for the one carrying {@link #ATTRIBUTE} rather than taking the
 * first it finds.
 *
 * Neither can answer when the classes were loaded from a directory rather than
 * a jar, as they are from an IDE or from build/classes. There is no version
 * there to report, and "unknown" is the honest answer.
 */
public final class Version {
	/** Marks a manifest as jkite's own. */
	public static final String ATTRIBUTE = "Jkite-Version";

	private static final String UNKNOWN = "unknown";

	private Version() {
	}

	public static String current() {
		Package pkg = Version.class.getPackage();
		String v = pkg != null ? pkg.getImplementationVersion() : null;
		return v != null ? v : fromManifests(Version.class.getClassLoader());
	}

	/** The version in the first manifest on the class path that is jkite's own. */
	static String fromManifests(ClassLoader loader) {
		try {
			Enumeration<URL> manifests = loader.getResources("META-INF/MANIFEST.MF");
			while (manifests.hasMoreElements()) {
				// not openStream(): for a jar: URL that goes through a cache of
				// open jars that outlives the class loader, and this reads one
				// short file once. Leaving a jar open for the life of the JVM
				// pins it, which on Windows means it cannot be deleted either.
				URLConnection connection = manifests.nextElement().openConnection();
				connection.setUseCaches(false);
				try (InputStream in = connection.getInputStream()) {
					String v = new Manifest(in).getMainAttributes().getValue(ATTRIBUTE);
					if (v != null) {
						return v;
					}
				} catch (IOException e) {
					// a manifest that cannot be read is simply not the one
				}
			}
		} catch (IOException e) {
			// no class path to search
		}
		return UNKNOWN;
	}
}
