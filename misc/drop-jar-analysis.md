# Dropping jkite.jar and being nothing but a bootstrap

> A decision record of a proposal that was **not** taken. It is kept because
> the proposal was a reasonable one and someone will think of it again. It
> describes the tree as it stood when the question was asked, so some names in
> it have since changed.

## The proposal

Reduce jkite to three jobs:

1. Get a JDK (asking before it downloads one)
2. Get JBang itself (upstream's own distribution)
3. Ask before JBang's dependency resolution downloads anything

Running the script would be upstream JBang's job. `jkite.jar` — the reduced
fork — would then not be needed at all.

## Against what exists

| | Today | Proposed |
| --- | --- | --- |
| What it is | a reduced fork as a jar (31 files under `src/main/java`) plus 6 launcher and bootstrap scripts | shell and cmd scripts only |
| Distribution | `jkite.jar` on a GitHub release, with version, URL and SHA-256 pinned in `jkite.properties` | pin upstream JBang's distribution and nothing else |
| Following upstream | `misc/sync-upstream.sh`, 9 mirrored files plus 3 shims to maintain | not needed; point at an upstream release |

## What would go away (the gain)

- 31 files under `src/main/java` and 7 under `src/test`, the Gradle build,
  shadowJar, the MIMA and maven-resolver dependencies,
  `JdkHttpTransporterFactory` and `jkiteRuntime` (written here to put the JDK's
  HttpClient in place of Apache's).
- The three-way mirror/shim split, `misc/sync-upstream.sh` and
  `misc/upstream-ref.txt`. When upstream fixes `Directives.java`, the fix
  arrives as a JBang release.
- Releasing at all (`misc/update-dist.sh <version>` → `gh release create` →
  commit `dist/`). Nothing would need publishing, and
  `jkite-bootstrap-jar` would become a script that fetches JBang's zip.
- The whole class of bug that is "this behaves differently from JBang".
  Directive handling would *be* upstream.

## What would be lost or change

Of the properties that today's `README.md` and `dist/README.md` state, these
cannot be kept without the jar.

1. **Running as a child process, and passing the exit status through.**
   Today the jar starts `java` as a child process sharing stdin, stdout and
   stderr, and returns the script's exit code unchanged. Upstream JBang does
   the opposite: it prints the `java` command line to stdout, exits 255, and
   the launcher `eval`s it. Going back to upstream would put the streaming in
   `jkite Hello.java | sort`, piped input, and the meaning of `$?` at the mercy
   of upstream's `bin/jbang`. (It works in practice, but "no protocol" is
   currently a selling point of the design, and it would be gone.)
   There is an upside as well: today jkite's own JVM stays resident for as long
   as the script runs, and with `eval` it would not.
2. **The reduction itself.** The subcommands (`edit`, `init`, `alias`,
   `catalog`, `trust`, `app`, `export`, …), remote scripts, gists, catalogs,
   `.jsh`/`.kt`/`.groovy`/`.md`, native image, and the build-time integrations
   (Quarkus and so on) would all come back. The small attack surface of "runs
   a single Java file, and that is all" would be lost. Restricting which
   arguments the wrapper passes could recover part of it, but that is
   reimplementation in a script again.
3. **The guarantee that `~/.jbang/currentjdk` is never written.** Today a
   `//JAVA` JDK only goes into the cache, so one run never changes which JDK
   the next run picks. Upstream JBang has `jdk default` and `currentjdk`, so
   that invariant would no longer be ours to keep.
4. **One route for getting JDKs.** Today the bootstrap JDK and the `//JAVA` JDK
   both come from the Coursier JVM index (`io.get-coursier.jvm.indices`), and a
   single variable points both at a mirror. Upstream JBang gets JDKs another
   way (the Disco/Foojay family), so anyone with a corporate mirror
   requirement would have to look at this again.
5. **Having few dependencies.** Today's jar carries Maven Resolver, MIMA and
   slf4j-nop, and HTTP is the JDK's own. Upstream JBang's jar is larger, and
   what is in it is not ours to choose.

## The real problem: how to ask before dependencies are downloaded

This is the technical heart of the proposal, and the one place where dropping
the jar leaves nothing to stand on. Upstream JBang has no hook for asking
before a download (`trust` is about remote scripts and does not apply to
`//DEPS`). Three ways to do it from a wrapper script, each with a real cost:

- **A. Run with `--offline` first; if it fails, ask, then run again.**
  The least code. The drawbacks: (1) it cannot say what is about to be
  downloaded, only that something is missing; (2) telling an offline failure
  from any other failure means reading the output, which is fragile; (3) even
  the successful path may compile twice.
- **B. Parse `//DEPS` / `--deps` in the script and list what is not already in
  the caches (`~/.m2`, `~/.jbang/cache`).**
  The question it asks would be a helpful one, but covering `@pom` BOMs,
  JitPack URL rewriting, `${property}` expansion and `//DEPS` reached
  transitively through `//SOURCES` amounts to **reimplementing the directive
  parser in shell** — which rather defeats dropping the jar. This is the
  classic way to drift from upstream.
- **C. Ask once, broadly.**
  "This run will fetch a JDK and dependencies from the network. Continue?",
  asked once per project, remembered afterwards. It cannot list exactly what,
  but it is a few dozen lines and is easy to disable from the environment for
  CI.

Realistically, **C as the default, with A alongside if needed**, is what fits
the goal of dropping the jar. If B is wanted, keeping the jar is cheaper.

## Non-interactive use (needed whichever way)

Any confirmation needs a defined behaviour when stdin is not a terminal.
Stopping silently in CI is the worst outcome, so it is a choice between:

- no confirmation and carry on when there is no terminal (the default), or
- skip it explicitly with `JKITE_ASSUME_YES=1` / `--yes`, and refuse when there
  is no terminal

Today's bootstrap scripts download without saying anything, so either way this
is a behaviour change for anyone already using it.

## Cost of migrating

- `jkite.properties` changes meaning (jkite's jar → JBang's distribution).
  Projects that have installed jkite would have to install again.
- The options passed to `jkite` (`-C`, `-R`, `--cds` and so on) would need a
  layer translating them to `jbang run`'s options. Thin, but not an exact
  match.
- The java-call-hierarchy-exporter, which prompted all of this, would have to
  be tried against both the differences in directive handling and the
  difference in how a script is run (point 1).

## Conclusion

Maintenance cost drops a great deal, and the largest debt — the difference
from upstream — disappears. What is lost is the value of being a reduced fork
at all: a small attack surface, the transparency of running as a child
process, the `currentjdk` invariant, and one route for getting JDKs.

It turns on two questions.

1. Is the reduction itself — "runs single-file Java, and nothing else" — a
   requirement, or is the requirement only to fetch JBang reproducibly? If the
   latter, the jar is unnecessary.
2. How precisely does the download confirmation have to describe what it is
   about to fetch? If one broad confirmation (C) is enough, the jar is
   unnecessary. If it has to list exactly what, a parser is needed, and so is
   the jar.
