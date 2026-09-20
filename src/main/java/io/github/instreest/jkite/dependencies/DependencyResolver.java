package io.github.instreest.jkite.dependencies;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.ConfigurationProperties;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.repository.SimpleArtifactDescriptorPolicy;

import eu.maveniverse.maven.mima.context.Context;
import eu.maveniverse.maven.mima.context.ContextOverrides;
import eu.maveniverse.maven.mima.runtime.standalonestatic.StandaloneStaticRuntime;
import dev.jbang.ExitException;
import io.github.instreest.jkite.Settings;
import io.github.instreest.jkite.spi.DownloadGate;
import io.github.instreest.jkite.spi.Providers;
import dev.jbang.util.Util;
import dev.jbang.dependencies.MavenCoordinate;
import dev.jbang.dependencies.MavenRepo;
import io.github.instreest.jkite.Version;

/**
 * Resolves Maven coordinates (including their transitive dependencies) to
 * local files using Maven Resolver through MIMA. Only Maven Central is used
 * as remote repository (plus mirrors/proxies from ~/.m2/settings.xml); the
 * local repository is jkite's own, under the cache, unless JKITE_MAVEN_REPO
 * names another - see Settings.getLocalMavenRepo for why it is not
 * ~/.m2/repository.
 */
public final class DependencyResolver {
	private final Set<MavenRepo> repositories = new LinkedHashSet<>();
	private final Set<String> dependencies = new LinkedHashSet<>();

	public DependencyResolver addRepositories(List<MavenRepo> repos) {
		repositories.addAll(repos);
		return this;
	}

	public DependencyResolver addDependencies(List<String> deps) {
		dependencies.addAll(deps);
		return this;
	}

	/** Resolves the collected dependencies. */
	public List<ArtifactInfo> resolve() {
		return resolve(new ArrayList<>(dependencies), new ArrayList<>(repositories));
	}

	/**
	 * Resolves the given coordinates and returns the artifacts on the class path
	 * in dependency order. Results are cached on disk (see {@link #cacheKey})
	 * and reused as long as the files are unchanged.
	 */
	public static List<ArtifactInfo> resolve(List<String> deps, List<MavenRepo> repos) {
		if (deps.isEmpty()) {
			return Collections.emptyList();
		}
		List<String> depIds = new ArrayList<>(new LinkedHashSet<>(deps));
		Util.verboseMsg("Resolving artifact(s): " + String.join(", ", depIds));
		if (!repos.isEmpty()) {
			Util.verboseMsg("Repositories: "
					+ repos.stream().map(MavenRepo::toString).collect(Collectors.joining(", ")));
		}
		String key = cacheKey(depIds, repos);
		if (!Util.isFresh()) {
			List<ArtifactInfo> cached = DependencyCache.find(key);
			if (cached != null) {
				Util.verboseMsg("Resolved artifact(s) from cache: " + cached);
				return cached;
			}
		}
		if (!Util.isOffline()) {
			// what the local Maven repository already holds needs no download and
			// is therefore not worth asking about; --fresh skips the shortcut
			// because it exists to go to the remote repositories again
			List<ArtifactInfo> local = Util.isFresh() || hasSnapshot(depIds)
					? null
					: resolveFromLocalRepo(depIds, repos);
			if (local != null) {
				Util.verboseMsg("Resolved artifact(s) without downloading: " + local);
				DependencyCache.store(key, local);
				return local;
			}
			Providers.downloadGate()
				.check(new DownloadGate.Request(DownloadGate.Kind.DEPENDENCIES,
						"Dependencies are missing locally and will be downloaded"
								+ (repos.isEmpty() ? " from Maven Central:" : ":"),
						depIds));
		}
		Util.infoMsg("Resolving dependencies...");
		try (Session resolver = new Session(Util.isOffline(), Util.isFresh(), repos)) {
			List<ArtifactInfo> artifacts = resolver.doResolve(depIds);
			Util.infoMsg("Dependencies resolved");
			DependencyCache.store(key, artifacts);
			Util.verboseMsg("Resolved artifact(s): " + artifacts);
			return artifacts;
		} catch (RuntimeException e) {
			throw offlineIsTheReason(e, depIds);
		}
	}

	/**
	 * Says that --offline is why, when it is.
	 *
	 * Asked to resolve something the local repository does not have, the
	 * resolver reports "Could not read artifact descriptor for ...", which is
	 * true and mentions neither the network nor the option that turned it off.
	 * The reason is in the third cause down, where nobody looks:
	 *
	 *   Cannot access central (...) in offline mode and the artifact ... has
	 *   not been downloaded from it before
	 *
	 * Somebody who passed --offline asked for exactly this and should be told
	 * so in the first line, with the coordinates they would need to fetch
	 * first. Only when offline: with the network on, the resolver's own
	 * message is the useful one and is left alone.
	 */
	private static RuntimeException offlineIsTheReason(RuntimeException e, List<String> depIds) {
		if (!Util.isOffline()) {
			return e;
		}
		return new ExitException(ExitException.EXIT_INVALID_INPUT,
				"Cannot resolve these without the network, and --offline was asked for:"
						+ System.lineSeparator() + "   " + String.join(System.lineSeparator() + "   ", depIds)
						+ System.lineSeparator()
						+ "Run once without --offline to put them in the local Maven repository.",
				e);
	}

	/**
	 * What a cached resolution was a resolution of: the coordinates, the
	 * repositories they were looked for in, and the local repository the result
	 * points into. The last one because the files a cache entry names live in
	 * that repository; pointed at another one, the same coordinates are another
	 * set of files, and answering from the entry would quietly keep using the
	 * old repository.
	 */
	private static String cacheKey(List<String> depIds, List<MavenRepo> repos) {
		Path localRepo = Settings.getLocalMavenRepo();
		return repos.stream().map(MavenRepo::toString).collect(Collectors.joining(","))
				+ "|" + localRepo.toAbsolutePath()
				+ "|" + String.join(Settings.CP_SEPARATOR, depIds);
	}

	/**
	 * True when one of the coordinates names a snapshot. The local repository
	 * holds whichever snapshot was last downloaded, and there is no telling
	 * from here whether a newer one has been published, so the shortcut below
	 * does not apply: asking a snapshot's repository is the point of a snapshot.
	 */
	private static boolean hasSnapshot(List<String> depIds) {
		return depIds.stream().anyMatch(id -> id.toUpperCase(Locale.ROOT).contains("-SNAPSHOT"));
	}

	/**
	 * Resolves the coordinates against the local Maven repository only, and
	 * returns null when that does not hold everything (which is the sign that a
	 * download would follow). Nothing is printed: this is a look, not a step of
	 * its own, and the real resolution reports what it does.
	 */
	private static List<ArtifactInfo> resolveFromLocalRepo(List<String> depIds, List<MavenRepo> repos) {
		try (Session offline = new Session(true, false, repos, true)) {
			return offline.doResolve(depIds);
		} catch (RuntimeException e) {
			Util.verboseMsg("Not everything is in the local Maven repository: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Resolves a single artifact (without its dependencies) and returns its
	 * local file. The coordinate may use a version range such as
	 * <code>[0,)</code> to get the newest available version. Because this goes
	 * through Maven Resolver, mirrors, proxies and credentials configured in
	 * <code>~/.m2/settings.xml</code> apply, and the result is cached in the
	 * local repository.
	 */
	public static Path resolveArtifact(String coord) {
		try (Session resolver = new Session(Util.isOffline(), Util.isFresh(), Collections.emptyList())) {
			return resolver.doResolveArtifact(coord);
		}
	}

	/** The local Maven repository in use, as the resolver resolved it. */
	public static Path getLocalMavenRepo() {
		try (Session r = new Session(true, false, Collections.emptyList())) {
			return r.context.repositorySystemSession().getLocalRepository().getBasedir().toPath();
		}
	}

	/**
	 * Refuses a repository that would be read over a plaintext connection.
	 * Everything else jkite fetches is https-only, and a dependency is the one
	 * download that becomes code running on the machine, so it cannot be the
	 * exception: over http, whoever carries the traffic chooses the jar, and the
	 * checksum that would catch them travels the same wire.
	 *
	 * A file: repository is allowed, since it touches no network, and so is
	 * plain http on the loopback address, which is how a test serves a
	 * repository to itself.
	 */
	static void requireSafeRepository(MavenRepo repo) {
		String url = repo.getUrl() == null ? "" : repo.getUrl().trim();
		String lower = url.toLowerCase(Locale.ROOT);
		if (lower.startsWith("https://") || lower.startsWith("file:")) {
			return;
		}
		if (lower.startsWith("http://")) {
			String host = url.substring("http://".length()).split("[/:?#]", 2)[0];
			if (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]")) {
				return;
			}
		}
		throw new ExitException(ExitException.EXIT_INVALID_INPUT,
				"Refusing to resolve dependencies from " + url + " (//REPOS " + repo.getId() + "):"
						+ " a repository is read over https, or from a file: path."
						+ " Over http the dependency, and the checksum that would catch it,"
						+ " are both chosen by whoever carries the traffic.");
	}

	/** A Maven Resolver session, configured the way jkite needs it. */
	private static final class Session implements Closeable {
	private final Context context;

	private Session(boolean offline, boolean updateCache, List<MavenRepo> repositories) {
		this(offline, updateCache, repositories, false);
	}

	private Session(boolean offline, boolean updateCache, List<MavenRepo> repositories, boolean silent) {
		Map<String, String> userProperties = new HashMap<>();
		// avoid being blocked by servers that reject the default "Java" user agent
		userProperties.put("aether.connector.userAgent", "jkite/" + Version.current());

		ContextOverrides.Builder overrides = ContextOverrides.create()
			.userProperties(userProperties)
			.offline(offline)
			.withUserSettings(true)
			.withLocalRepositoryOverride(Settings.getLocalMavenRepo())
			.repositories(toRemoteRepositories(repositories))
			.addRepositoriesOp(ContextOverrides.AddRepositoriesOp.REPLACE)
			// Deliberately Maven's own behaviour rather than the stricter rule
			// the pinned downloads follow. A repository a script names may be
			// an internal one that publishes no checksums at all, and jkite is
			// not the place to make a build fail where Maven would not: which
			// dependency a project gets is the project's own decision, and its
			// repository's. What jkite does insist on is the connection it
			// arrives over - see requireSafeRepository.
			.checksumPolicy(ContextOverrides.ChecksumPolicy.WARN)
			.snapshotUpdatePolicy(updateCache ? ContextOverrides.SnapshotUpdatePolicy.ALWAYS : null);
		if (!silent && !Util.isQuiet()) {
			overrides.repositoryListener(new ProgressListener());
		}
		this.context = new StandaloneStaticRuntime().create(overrides.build());
	}

	@Override
	public void close() {
		context.close();
	}

	/**
	 * Maven Central, or whatever //REPOS (or --repos) named instead of it -
	 * naming any repository replaces Central rather than adding to it. Mirrors,
	 * proxies and credentials still come from ~/.m2/settings.xml.
	 */
	private static List<RemoteRepository> toRemoteRepositories(List<MavenRepo> repositories) {
		if (repositories.isEmpty()) {
			return Collections.singletonList(ContextOverrides.CENTRAL);
		}
		return repositories.stream()
			.map(r -> {
				requireSafeRepository(r);
				return new RemoteRepository.Builder(r.getId(), "default", r.getUrl()).build();
			})
			.collect(Collectors.toList());
	}



	private Path doResolveArtifact(String coord) {
		Artifact artifact = toArtifact(coord);
		String version = artifact.getVersion();
		if (version.startsWith("[") || version.startsWith("(")) {
			try {
				VersionRangeResult range = context.repositorySystem()
					.resolveVersionRange(context.repositorySystemSession(),
							new VersionRangeRequest()
								.setArtifact(artifact)
								.setRepositories(context.remoteRepositories()));
				if (range.getHighestVersion() == null) {
					throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
							"No version of " + coord + " is available");
				}
				artifact = artifact.setVersion(range.getHighestVersion().toString());
			} catch (VersionRangeResolutionException e) {
				throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
						"Could not resolve version range of " + coord + ": " + e.getMessage(), e);
			}
		}
		try {
			ArtifactResult result = context.repositorySystem()
				.resolveArtifact(context.repositorySystemSession(),
						new ArtifactRequest()
							.setArtifact(artifact)
							.setRepositories(context.remoteRepositories()));
			return result.getArtifact().getFile().toPath();
		} catch (ArtifactResolutionException e) {
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
					"Could not resolve " + coord + ": " + e.getMessage(), e);
		}
	}


	private List<ArtifactInfo> doResolve(List<String> depIds) {
		context.repositorySystemSession().getData().set("depIds", depIds);
		// Maven is by default "forgiving" for dependency POM loading: here we want to
		// ensure that all enlisted deps exists for sure
		DefaultRepositorySystemSession strictSession = new DefaultRepositorySystemSession(
				context.repositorySystemSession());
		strictSession.setArtifactDescriptorPolicy(new SimpleArtifactDescriptorPolicy(false, false));
		try {
			Map<String, List<Dependency>> scopeDeps = depIds.stream()
				.map(coord -> toDependency(toArtifact(coord)))
				.collect(Collectors.groupingBy(Dependency::getScope));

			List<Dependency> deps = scopeDeps.getOrDefault(JavaScopes.COMPILE, Collections.emptyList());
			List<Dependency> managedDeps = deps.stream()
				.flatMap(d -> getManagedDependencies(strictSession, d).stream())
				.collect(Collectors.toList());

			if (scopeDeps.containsKey("import")) {
				// @pom coordinates are BOMs: their managed dependencies are applied
				// to the ordinary dependencies (which may then omit a version)
				List<Dependency> boms = scopeDeps.get("import");
				List<Dependency> mdeps = boms.stream()
					.flatMap(d -> getManagedDependencies(strictSession, d).stream())
					.collect(Collectors.toList());
				deps = deps.stream().map(d -> applyManagedDependencies(d, mdeps)).collect(Collectors.toList());
				managedDeps.addAll(0, mdeps);
			}

			CollectRequest collectRequest = new CollectRequest()
				.setManagedDependencies(managedDeps)
				.setDependencies(deps)
				.setRepositories(context.remoteRepositories());
			DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, null);
			List<ArtifactResult> artifacts = context.repositorySystem()
				.resolveDependencies(context.repositorySystemSession(), dependencyRequest)
				.getArtifactResults();
			return artifacts.stream()
				.map(ArtifactResult::getArtifact)
				.map(Session::toArtifactInfo)
				.collect(Collectors.toList());
		} catch (DependencyResolutionException ex) {
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
					"Could not resolve dependencies: " + ex.getMessage(), ex);
		}
	}

	private Dependency applyManagedDependencies(Dependency d, List<Dependency> managedDeps) {
		Artifact art = d.getArtifact();
		if (art.getVersion().isEmpty()) {
			Optional<Artifact> ma = managedDeps.stream()
				.map(Dependency::getArtifact)
				.filter(a -> a.getGroupId().equals(art.getGroupId())
						&& a.getArtifactId().equals(art.getArtifactId()))
				.findFirst();
			if (ma.isPresent()) {
				return new Dependency(ma.get(), d.getScope(), d.getOptional(), d.getExclusions());
			}
		}
		return d;
	}

	private List<Dependency> getManagedDependencies(RepositorySystemSession session, Dependency dependency) {
		return resolveDescriptor(session, dependency.getArtifact()).getManagedDependencies();
	}

	private ArtifactDescriptorResult resolveDescriptor(RepositorySystemSession session, Artifact artifact) {
		try {
			if (artifact.getVersion().trim().isEmpty()) {
				return new ArtifactDescriptorResult(
						new ArtifactDescriptorRequest(artifact, context.remoteRepositories(), ""));
			}
			// the version may be a range; descriptors can only be read for exact versions
			VersionRangeRequest rangeRequest = new VersionRangeRequest()
				.setArtifact(artifact)
				.setRepositories(context.remoteRepositories());
			VersionRangeResult rangeResult = context.repositorySystem().resolveVersionRange(session, rangeRequest);
			if (rangeResult.getVersions().isEmpty()) {
				throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
						"Could not resolve version range: " + artifact);
			}
			String version = rangeResult.getVersions().get(rangeResult.getVersions().size() - 1).toString();
			ArtifactDescriptorRequest descriptorRequest = new ArtifactDescriptorRequest()
				.setArtifact(artifact.setVersion(version))
				.setRepositories(context.remoteRepositories());
			return context.repositorySystem().readArtifactDescriptor(session, descriptorRequest);
		} catch (VersionRangeResolutionException | ArtifactDescriptorException ex) {
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
					"Could not read artifact descriptor for " + artifact, ex);
		}
	}

	private static Dependency toDependency(Artifact artifact) {
		return new Dependency(artifact,
				"pom".equalsIgnoreCase(artifact.getExtension()) ? "import" : JavaScopes.COMPILE);
	}

	private static Artifact toArtifact(String coord) {
		MavenCoordinate c = MavenCoordinate.fromString(coord);
		return new DefaultArtifact(c.getGroupId(), c.getArtifactId(), c.getClassifier(), c.getType(),
				c.getVersion() != null ? c.getVersion() : "");
	}

	private static ArtifactInfo toArtifactInfo(Artifact artifact) {
		MavenCoordinate coord = new MavenCoordinate(artifact.getGroupId(), artifact.getArtifactId(),
				artifact.getVersion(), artifact.getClassifier(), artifact.getExtension());
		return new ArtifactInfo(coord, artifact.getFile().toPath());
	}

	/** Prints the coordinates the user asked for while they are being resolved. */
	private final class ProgressListener extends AbstractRepositoryListener {
		@Override
		public void artifactResolving(RepositoryEvent event) {
			print(event.getArtifact());
		}

		@Override
		public void artifactDownloading(RepositoryEvent event) {
			print(event.getArtifact());
		}

		@SuppressWarnings("unchecked")
		private void print(Artifact art) {
			RepositorySystemSession session = context.repositorySystemSession();
			List<String> depIds = (List<String>) session.getData().get("depIds");
			if (depIds == null) {
				return;
			}
			Set<String> ids = (Set<String>) session.getData().computeIfAbsent("ids", () -> new HashSet<>(depIds));
			Set<String> printed = (Set<String>) session.getData().computeIfAbsent("printed", HashSet::new);
			String id = art.getGroupId() + ":" + art.getArtifactId();
			String coord = id + ":" + art.getVersion();
			if (!printed.contains(id) && (ids.contains(id) || ids.contains(coord) || Util.isVerbose())) {
				Util.infoMsg("   " + coord);
				printed.add(id);
			}
		}
	}
	}
}
