# Depending on JBang as a library, and keeping the downloading here

> A decision record, kept because the decisions in it were close ones and the
> reasoning is not visible from the code. It is written in the order the
> questions were asked, so a later section sometimes corrects an earlier one;
> where it does, it says so. **What jkite settled on is at the end of each
> part, not at the top.**

The aim is not jar size. It is **maintenance**, and making the dependency on
JBang an **explicit interface boundary**. Code written here is replaced by an
external library where there is a clear reason to.

## 1. The conclusion, first

- **Technically possible.** All the parser (`Directives`) asks of JBang is 13
  pure helpers, and none of them touches the network.
- **But the artifact has stopped being published.** On Maven Central,
  `dev.jbang:jbang-cli` **stops at 0.132.1 (2025-10-04)**, while the
  distribution `dev.jbang:jbang.bin` has gone on to **0.141.0 (2026-07)**.
  Depending on it as a library would pin us **further back** than the mirror,
  which can follow `upstream/main`.
  → Making the parser a library dependency should **wait** until publication
  resumes. *(Corrected later: the artifact was renamed, not stopped. See the
  last part.)*
- **`dev.jbang:devkitman` (0.4.12, active), on the other hand, is ready now.**
  It is the JDK management JBang split out into a library of its own, with
  `JdkProvider` / `JdkInstaller` / `RemoteAccessProvider` as its SPI.
  `RemoteAccessProvider.downloadFromUrl(String)` is a single method and an
  **ideal seam for asking before a download happens**. It could replace the
  `jdk/` package written here (5 files, 1039 lines).

## 2. How tightly the parser is actually coupled

This is everything `Directives.java` asks of JBang, extracted mechanically
from the mirrored source:

```
Util.explode / isPattern / basePathWithoutPattern / isValidPath
Util.isValidClassIdentifier / isValidModuleIdentifier / stringLines / warnMsg
JavaUtil.RequestedVersionComparator
DependencyUtil.looksLikeAGav / looksLikeAPossibleGav
JitPackUtil.possibleMatch
MavenCoordinate.DEFAULT
```

All of it is string and path handling. **Nothing goes through downloading,
catalogs or trust.** Upstream's `Util` holds some static mutable state
(verbose/quiet/offline/fresh/cwd) and nothing more; there is no heavy static
initializer (checked with `javap`). So "parse upstream, download here" is not a
strained design. The three shim files (826 lines) exist precisely to satisfy
those 13 members, and a library dependency would end that upkeep.

The problem is not the coupling, it is **supply**:

| | Now (mirror) | As a library |
| --- | --- | --- |
| What we can follow | any commit on `upstream/main` | only what is published to Central |
| Newest available | upstream main | 0.132.1 (9 releases behind) |
| API stability | we read the diff ourselves | `dev.jbang.source.parser` is internal API and changes without notice |
| Effort | `sync-upstream.sh` + build | one version number |

The `jbang-cli` pom pulls 17 dependencies — picocli, qute, jsoup, gson,
maven-model, MIMA and the rest. The parse path uses only jspecify, so they
could be narrowed with `exclude`, but that is a decision about whether to
accept unused dependencies arriving transitively.

## 3. The architecture this suggests: the dependency boundary as an interface

The point is to **keep JBang's types out of the application**.

```
io.github.instreest.jkite.spi
  ScriptSpec          // our own DTO for a parse result (deps, repos, sources,
                      // files, javaVersion, mainClass, module, options ...)
  ScriptParser        // ScriptSpec parse(Path, Map<String,String> props)
  DownloadGate        // boolean allow(DownloadRequest)  <- the only place that asks
  JdkService          // Path resolve(RequestedVersion)
  DependencyService   // List<Path> resolve(ScriptSpec)
```

- Allow two implementations of `ScriptParser`:
  - `MirroredDirectivesParser` (today's mirror; the default)
  - `JBangLibraryParser` (depends on `dev.jbang:jbang-cli`, if publishing
    resumes)

  Run the same **conformance test** against both. That makes "use the library"
  a decision that can be taken later rather than now.
- Put every download behind `DownloadGate`. There are two seams, and both are
  already our own code, so where to put it is not in doubt:
  - Maven: `JdkHttpTransporterFactory` (our transport)
  - JDK: `jdk/Downloader` (or `RemoteAccessProvider`, with devkitman)

  Gathering them there puts the confirmation, offline, mirrors and proxies in
  one place.

## 4. An inventory of what is written here, and whether a library could take it

| Ours | Lines | Candidate | Verdict |
| --- | --- | --- | --- |
| `jdk/` (Downloader, Jdk, JdkIndex, JdkManager, Unpacker) | 1039 | **`dev.jbang:devkitman` 0.4.12** | **Declined**, to keep faith with the existing jkite scripts. For reference, the case for it: **recommended**; JBang itself uses it; the SPI is the seam for a confirmation prompt. To check: it defaults to Foojay, a different route from the Coursier JVM index used here (whether `MetadataJdkInstaller` can bring them together needs testing). `JBangJdkProvider` exists, so cache layout compatibility is reachable |
| `JdkHttpTransporterFactory` | 259 | `maven-resolver-transport-jdk` | **On hold.** It exists only in the 2.x line, and today's MIMA 2.4.x is on resolver 1.9.x. When MIMA 3.0 (currently alpha) settles, all 259 lines go. **Worth tracking** |
| `util/Json.java` | 206 | gson | **Yes.** devkitman brings gson in transitively, so it would cost nothing extra |
| `util/OsDetector.java` | 121 | devkitman `OsUtils` / os-maven-plugin | Marginal. `${os.detected.*}` compatibility is a requirement, so keeping ours is cheaper than replacing it |
| `source/parser` (mirror) | 643 | `dev.jbang:jbang-cli` | **On hold** (section 2) |
| `Util`/`JavaUtil`/`DependencyUtil` (shims) | 826 | as above | They disappear if the parser becomes a library |
| `MainClassFinder`, `ModuleUtil`, `AppBuilder`, `CmdGenerator` | — | none | Stay ours |

## 5. The order to do it in

1. **[done]** Split out the `spi` package and introduce `DirectiveParser` /
   `DownloadGate`. Implementations unchanged; only the boundary is added
   (behaviour identical, held by tests).
2. **[done]** Implement the confirmation in `DownloadGate`, including the
   default when there is no terminal, and `JKITE_ASSUME_YES`.
3. **[declined]** Replace `jdk/` with devkitman, routing `RemoteAccessProvider`
   through `DownloadGate`. The Coursier index requirement would be settled here.
4. Adopt gson and delete `util/Json.java`.
5. Wait for MIMA 3 to settle, then move to `maven-resolver-transport-jdk`.
6. If `jbang-cli` is published again, add `JBangLibraryParser`; if the
   conformance test passes, delete the mirror and the shims.

---

# Steps 4 onward, reconsidered after step 3 was declined

Declining step 3 (replacing `jdk/` with devkitman) knocked the ground out from
under both 4 and 5. What follows is the re-examination.

## What decides it

Ours or external is decided by four things, and not by size.

1. **Is the specification someone else's?** Interpreting an external format
   (tar, JSON, JDK distribution layout) ourselves means that failing to keep up
   shows as a **silent wrong answer** rather than as an error.
2. **Who decides the input?** Input we fetched from a URL we chose, or input
   whose content a third party can decide.
3. **Supply risk.** Will the dependency keep being released, or has it stopped
   or been left in alpha?
4. **Fit with what exists.** Does it break cache layout or the jkite scripts?
   That is why step 3 was declined.

## 4. `util/Json.java` → gson: **declined**

The premise is gone. Without step 3, gson does not arrive transitively, so this
would be a **new direct dependency** (there is no gson on today's
runtimeClasspath, and no `com/google` entry in the jar).

- The only input is the Coursier JVM index. We choose the URL and no third
  party decides its content (point 2 is in our favour)
- There is one call site, `JdkIndex.java:108`
- 206 lines, read-only, producing nothing but `Map`/`List`/`String`/`Double`

→ **Keep ours.** But `Json.java` has no tests, so **add unit tests**: malformed
JSON, nesting, escapes, numbers. The reason to adopt gson would be starting to
use JSON for something else, and that has not happened.

## 5. `JdkHttpTransporterFactory` → `maven-resolver-transport-jdk`: **on hold indefinitely**

Last time this said "when MIMA 3 settles". **MIMA 3 is not coming.**

| | |
| --- | --- |
| MIMA 3.0.0-alpha-3 (resolver 2.x, includes transport-jdk) | **stopped at 2024-01-19**; no release in two and a half years |
| MIMA 2.4.x (current) | 2.4.48 on 2026-08-07, still on **resolver 1.9.27** |
| `maven-resolver-transport-jdk` | **exists only in the 2.x line** |

Three roads, none of them worth it now:

- **(a) Keep ours** — 259 lines of transport, with tests
  (`TestJdkHttpTransporter`, 203 lines)
- **(b) Drop MIMA and use resolver 2.x directly** — we would have to assemble
  `settings.xml`, mirrors, proxies and authentication ourselves, which is far
  more code, and works against the point of step 1: fewer boundaries
- **(c) Go back to `maven-resolver-transport-apache`** — our 259 lines go, but
  Apache HttpClient 5, Gson and the public suffix list come back, which is
  where we started

→ **(a).** What to watch is not jbang but **MIMA's releases**, and the trigger
to reconsider is MIMA's current line moving to resolver 2.x.

## 6. The parser as a library: **waiting, on a stated condition**

This is the one that could genuinely change. Fixing the condition in advance:

- **Start when** `dev.jbang:jbang-cli` is published in a version newer than
  0.132.1 (it stops at 0.132.1 / 2025-10-04, while `jbang.bin` continues to
  0.141.0 / 2026-07)
- **Cost to start**, given step 1 is done: one `JBangLibraryParser` class and a
  conformance test extending `TestDirectiveParser`. Nothing above is touched
- **If it is never published again**: no change, and the boundary from step 1
  is not wasted (`Project` no longer names an upstream type)

## An extra candidate: `jdk/Unpacker.java`, missing from the inventory

It was left out of the table above. **Of everything in 4 to 6, this has the
best return.** It is 228 lines of hand-written tar/zip extraction, and both
point 1 (external specification) and point 2 (input) apply to it.

What reading the implementation turned up:

| | |
| --- | --- |
| No pax header (`x`/`g`) support | For a distribution where a path over 100 characters is only expressed in pax, it **silently writes a truncated name** rather than failing |
| Link targets are not checked | Entry paths are checked lexically, but the classic escape remains: create a symlink pointing outward, then let a later entry write **through** it |
| A hardlink (`1`) is created as a symlink | Little practical harm, but not faithful |

The threat model is weak: the URL comes from a pinned index and the SHA-256 is
verified. But `JdkManager.verifyChecksum` **warns and continues when the
published checksum cannot be fetched**, and on that path the trust is bare.

The reason step 3 was declined (fitting the jkite scripts) was about **getting**
a JDK; **unpacking** is internal, so that constraint does not apply. Two
choices:

- **(A) Bring in `commons-compress`** — one added dependency, itself with none.
  It handles pax, the GNU extensions, links and permissions as specified. jbang
  and devkitman both use it
- **(B) Close the holes ourselves** — pax header support and link target
  checking, roughly +50 lines, plus tests that feed it a hostile tar

(B) if the point is to add no dependencies, (A) if the policy is that keeping
up with someone else's format is someone else's job. Either way, **the hostile
tar tests are needed**.

## Conclusion

- Nothing in 4, 5 or 6 is worth doing now
- What is worth doing is **Unpacker** ((A) or (B), plus tests) and **tests for
  `Json.java`**
- 4 declined, 5 waiting on MIMA (indefinitely), 6 waiting on jbang-cli being
  published again (on the condition above)

---

# What was done (4, 5 and Unpacker)

Following a change of policy — size is no longer a constraint — the decisions
above were revisited and these were carried out.

| | Decision | Result |
| --- | --- | --- |
| 5, transport | from "on hold indefinitely" to **(c), back to the stock HTTP transport** | Deleted `JdkHttpTransporterFactory` (259 lines), `jkiteRuntime` (52) and their tests (203). MIMA's `StandaloneStaticRuntime` is used as it comes |
| Unpacker | **(A) commons-compress** | Replaced the hand-written tar/zip readers. Pax, the GNU extensions, links and permissions are Commons Compress's problem now |
| 4, Json | **moved to gson** | Deleted `util/Json.java` (206 lines). The transport brings gson, so it costs nothing |

## Things the investigation turned up

- On resolver 1.9.x the stock Apache transport's artifactId is
  `maven-resolver-transport-http` (`-apache` is the 2.x name). It brings
  **HttpClient 4.5.14 / HttpCore 4.4.16 / commons-codec / gson /
  jcl-over-slf4j**. **Not** HttpClient 5
- HttpClient 4.5.x is the end of the 4 line and the same one Maven 3.9.x uses.
  Settled, but nothing new will arrive. If MIMA moves to resolver 2.x we can
  go to HttpClient 5 (`maven-resolver-transport-apache`) or to
  `maven-resolver-transport-jdk`
- commons-compress 1.28 pulls commons-io, commons-lang3 and commons-codec. Only
  tar+gzip+zip are used, so excluding them might work, but an untested path
  would then fail with `NoClassDefFoundError`, so they were left in
- The jar went from **2.26 MB to 6.46 MB**. Compressed: commons 2.3 MB,
  HttpClient/Core 0.85 MB, resolver/maven 0.55 MB, gson 0.24 MB, jkite's own
  code 0.14 MB

## What it did for security

Of the three holes in Unpacker:

- **No pax support** (silently truncating long paths) → gone, with a test
- **Link targets unchecked** → a link pointing outside the output directory is
  refused, and on every write the parent directory's real path is checked, so a
  write that goes through a link is refused too. Both tested
- **Hardlinks created as symlinks** → same behaviour as before, but the link
  target check now applies

---

# Correcting the condition on 6: the artifact is `jbang.bin`, and it is still published

Saying `dev.jbang:jbang-cli` had stopped at 0.132.1 was wrong: **the artifact
was renamed**. It continues as `dev.jbang:jbang.bin`, whose pom `<name>` is
still "JBang CLI". Publication never stopped.

| | |
| --- | --- |
| Newest | **0.141.0 (2026-07-13)**, after 0.135.1 (2025-12) → 0.136.0 → 0.137.0 → 0.138.0 → 0.140.1 → 0.141.0 |
| Artifacts | `jbang.bin-0.141.0.jar` (classes only, 1.1 MB), `-all.jar` (everything, 14.9 MB), `-sources.jar`, `-javadoc.jar`, `.asc` signatures |

So **the condition for depending on it as a library is already met.**

## Compatibility, from reading the 0.141.0 jar

- `Directives`' public API is an **exact match for the mirror in this fork**
  (25 members). `Directives.Extended(String, Function<String,String>)` is
  public too — the very constructor `MirroredDirectiveParser` calls
- All 13 members the parser needs exist, with matching signatures
  (`Util.explode` / `isPattern` / `basePathWithoutPattern` / `isValidPath` /
  `isValidClassIdentifier` / `isValidModuleIdentifier` / `stringLines` /
  `warnMsg`, `JavaUtil.RequestedVersionComparator` / `checkRequestedVersion`,
  `DependencyUtil.looksLikeAGav` / `looksLikeAPossibleGav`,
  `JitPackUtil.possibleMatch`, `MavenCoordinate.DEFAULT_VERSION`)

`JBangLibraryParser` could be written as very nearly a copy of
`MirroredDirectiveParser` and held by a conformance test extending
`TestDirectiveParser`. The boundary from step 1 is usable as it stands.

## The new question: the whole CLI's dependencies come with it

The condition is met, but a different judgement is now needed. `jbang.bin`'s
pom lists the runtime dependencies of the entire CLI, and parsing needs almost
none of them:

```
devkitman, commons-text, commons-compress, aesh(+readline), qute-core,
plexus-java, gson, jsoup, java-properties, slf4j-nop, jcl-over-slf4j,
jandex, mima(context, standalone-static), domtrip-core, domtrip-maven,
tamboui-toolkit, tamboui-aesh-backend, os-source, ...
```

The parse path really uses jspecify alone. aesh (readline), tamboui (a TUI) and
jsoup (HTML) would all end up inside `jkite.jar`. `-all.jar` being 14.9 MB says
plainly how much that is.

**This runs straight into the policy of not excluding dependencies by hand.**
Three roads:

| | |
| --- | --- |
| (a) Take the dependencies whole | Faithful to the policy. The jar goes from 6.5 MB to the mid teens, shipping a TUI and an HTML parser that are never used |
| (b) Narrow them with `exclude` | The jar stays small, but the dependency tree is then pruned by hand, against the policy, and the day the parser calls another `Util` method it fails with `NoClassDefFoundError` |
| (c) Keep mirroring | As today. Nine mirrored files, followed with `sync-upstream.sh`. Thanks to the boundary from step 1, `Project` is already free of upstream types |

One more thing weighs in: the same reasoning used for 5 (transport) — react
when jbang changes, do not watch — applies here. **The mirror's upkeep is
measurably low**: whole files are copied, so an upstream commit touching
hundreds of unrelated files costs nothing here. The cost of (a), by contrast,
is paid forever.

## Decision: (c), keep mirroring

**6 is no longer "waiting to be published"; it is "(c) was chosen".** The
condition is met, but carrying the whole CLI's dependencies costs more than
copying files wholesale.

What would reopen it is **not publication, but the sync actually becoming
expensive**:

- `Directives` starts pulling new dependencies
- the shims (`Util` / `JavaUtil` / `DependencyUtil`) need attention at every sync
- an upstream change lands that `TestDirectiveParser` does not pass

Until then there is no need to follow `jbang.bin` releases. The route to take
if it is ever started (`JBangLibraryParser` plus a conformance test) is above,
and thanks to the step 1 boundary nothing above it is touched.

This decision is recorded in the code as well — the javadoc of
`DirectiveParser`, `misc/upstream-mirror.txt`, and "Staying in step with JBang"
in `docs/DEVELOPMENT.md` — so it is findable without reading this file.

# The condition on 5 (transport), updated

Not "when MIMA moves to resolver 2.x" but **"when jbang itself changes
transport"**. No periodic watching needed.
