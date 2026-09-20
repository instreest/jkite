# jkite

**Ship a Java tool that anyone can run straight after `git clone`.** Commit a
`jkite/` directory next to your tool, and whoever checks the project out
runs it with one command.

They do not need a JDK installed. They do not need *the* JDK your tool asks
for, even if the one they have is older or newer. They do not fetch your
dependencies, and there is no build to explain. None of that is their problem
any more, and none of it is yours to support.

```bash
jkite/jkite tools/Report.java --since 2026-01
```

That works because the tool declares what it needs, in the tool:

```java
//JAVA 21+
//DEPS org.apache.commons:commons-csv:1.12.0
//SOURCES report/*.java
```

jkite reads those directives, installs a JDK if the machine has none,
resolves the dependencies from Maven Central, compiles, and runs. jkite
itself, and the JDK that starts it, are pinned by a SHA-256 committed with
your project; the `//JAVA` JDK and the dependencies are verified against the
checksums their publishers serve. Everything is fetched once per machine, so
the second tool and the second checkout cost nothing.
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) says which is which.

The directives are JBang's, parsed by JBang's own parser. jkite is a
reduced fork of [JBang](https://github.com/jbangdev/jbang) that does this one
thing. It is not affiliated with, endorsed by, or supported by the JBang
project; please report problems here, not to them.

## Quick start

For the tool's author, once:

```bash
curl -fsSL https://github.com/instreest/jkite/releases/latest/download/install.sh | bash
git add jkite && git commit -m "Add jkite"
```

For everyone else, nothing. Put this in your project's README:

````markdown
## Running the tools

```bash
jkite/jkite tools/Report.java      # macOS, Linux, WSL
jkite\jkite.cmd tools\Report.java  # Windows
```
````

`install.cmd` installs from a Windows command prompt. The installer writes
eleven files, about 98 kB, into `jkite/`; commit all of them, the way a
Gradle or Maven wrapper is committed.

## What gets committed, and what gets downloaded

`jkite/` holds the launcher scripts and `jkite.properties`, which pins
everything a run may need to fetch:

```properties
distributionVersion=0.2.0
distributionUrl=https://github.com/instreest/jkite/releases/download/v0.2.0/jkite.jar
distributionSha256Sum=dbc5414a...
bootstrapJdkVersion=25.0.3
bootstrapJdkUrl.linux-amd64=https://github.com/adoptium/...tar.gz
bootstrapJdkSha256Sum.linux-amd64=69264a7a...
```

Neither `jkite.jar` nor a JDK is committed. On the first run the launcher
downloads what it is missing into `~/.jkite`, checks it against the SHA-256
above, and keeps it there for every project on the machine. So a project's
history carries scripts, not binaries, and moving to a new version changes a
few lines rather than megabytes.

A project that would rather not depend on the download can put a
`jkite.jar` into `jkite/` itself: it wins over the properties, and then
only a JDK is ever fetched.

### What is trusted

Worth being plain about, since the first command above is `curl ... | bash`.

What that command trusts is https and GitHub: there is no signature on
`install.sh`, and there is nothing a first install could check one against.
Every other download is verified. The installer fetches the rest of `jkite/`
from the same release over https, and everything after that is pinned by
SHA-256 in the `jkite.properties` you commit — so `jkite.jar` and the JDK,
which are the large downloads and the ones that become code, are checked
against a value in your own repository rather than against whatever the
network serves.

Read `install.sh` before running it if you would rather not pipe it:
`curl -fsSL <url> -o install.sh`, read it, `bash install.sh`. It is about a
hundred lines. A dropped connection cannot leave a half-installation either
way — the whole script is one function called on its last line, so a truncated
copy does nothing at all rather than part of the job.

## Updating

Which jkite a project runs is a property of the project, as it is for the
Gradle and Maven wrappers: it is what `jkite/jkite.properties` says, it
is committed, and whoever clones the project gets it. The tool's author
updates it; everyone else receives it with `git pull`.

```bash
jkite/jkite --update            # the newest release
jkite/jkite --update v0.3.0     # or a particular one
git add jkite && git commit -m "Update jkite to 0.3.0"
```

`--update` re-runs the installer next to it and replaces every file. It is
answered by the launcher script, so it needs neither the jar nor a JDK: an
installation whose pinned jar can no longer be downloaded can still update
itself out of that state. It leaves a vendored `jkite.jar` alone, and warns
that the old jar still wins.

`--version` names the version that will actually run, and downloads nothing:

```
$ jkite/jkite --version
jkite 0.3.0
  pinned 0.3.0 by /home/me/tool/jkite/jkite.properties
  jar 0.3.0 at /home/me/.jkite/cache/jkite/0.3.0/jkite.jar
```

Ordinary runs never check for a new version. Nothing reaches the network
unless a jar or a JDK is actually missing.

## Directives

What a script needs is declared in the script. These are applied:

| Directive | Behaviour |
| --- | --- |
| `//JAVA <version>[+]` | the JDK to build and run with, installed when the machine has none |
| `//DEPS <gav>` | resolved from Maven Central; `@pom` entries act as BOMs |
| `//SOURCES <file-or-glob>` | compiled together with the script, recursively; every other source it needs is named here |
| `//FILES [<target>=]<file-or-glob>` | copied into the jar, optionally under another name |
| `//REPOS [<id>=]<url-or-alias>` | the Maven repositories to resolve from, over https or from a `file:` path. Naming any **replaces** Maven Central rather than adding to it |
| `//MAIN <class>` | the class to run, when there is more than one `main` |
| `//COMPILE_OPTIONS`, `//JAVAC_OPTIONS` | passed to `javac` |
| `//RUNTIME_OPTIONS`, `//JAVA_OPTIONS` | passed to `java` |
| `//MANIFEST <key>=<value>` | added to the jar manifest; `Add-Opens`, `Add-Exports` and `Enable-Native-Access` also reach the `java` command line |
| `//PREVIEW` | compiles and runs with `--enable-preview` |
| `${property}` in any directive | system properties, `-Dkey=value` and `os.detected.*` |

Every other directive JBang defines is parsed and ignored, so a script that
uses one still runs: `//MODULE`, `//CDS`, `//JAVAAGENT`, `//GAV`,
`//DESCRIPTION`, `//DOCS`, `//NOINTEGRATIONS`, `//NATIVE_OPTIONS`, `//GROOVY`
and `//KOTLIN`.

One thing JBang accepts is not ignored quietly: a `//DEPS` that names a
`.java` file rather than a Maven coordinate. jkite does not fetch and build
that file, so the class it was to provide is missing and the compile fails
naming it. Say what the script is made of with `//SOURCES` instead.

## Options

There are no subcommands. jkite runs the script, and only `--help`,
`--version`, `--update` and `--clear-cache` do something else.

```
jkite [<options>] <script.java> [<args>...]
```

| Option | |
| --- | --- |
| `-h`, `--help` | print the help and exit |
| `-V`, `--version` | print the version and exit |
| `--update [<ref>]` | update this installation and exit |
| `--verbose` | print what is being done |
| `--quiet` | only print errors |
| `--fresh` | ignore the caches and rebuild |
| `--clear-cache` | remove the built jars, the dependency jars and the resolved class paths, and exit. It prints the directories before it empties them; the installed JDKs are kept, and so is a repository `JKITE_MAVEN_REPO` names |
| `-o`, `--offline` | never access the network |
| `-Dkey=value` | a system property, for `${...}` in directives and for the script |
| `-R<option>` | an extra JVM option for the script |
| `-y`, `--yes` | download what is missing without asking |

Options may appear anywhere before the script, `--` ends them, and everything
after the script is the script's. `-V`, `--version` and `--update` are the
exception: the launcher answers those itself, without downloading anything, and
only when they are the first argument — that is the form that reports where the
jar and the pinning come from, and it is the one to paste into a bug report.
Later on they reach the jar, which answers `--version` with the bare version
and `--update` with an error, since updating is the launcher's job.

There is deliberately no option that overrides what a script's directives say:
the tool's author decides what the tool needs, not whoever runs it. The
environment can still add to them, though, through the `JBANG_APP_*` variables
the JBang parser reads - `JBANG_APP_RUNTIME_OPTIONS`,
`JBANG_APP_COMPILE_OPTIONS` and the like append to the matching directive. They
keep JBang's names because the parser is JBang's, unchanged.

## Environment

| Variable | |
| --- | --- |
| `JKITE_CONFIRM_DOWNLOADS` | whether a download is confirmed first: `auto` (default), `always`, `never` |
| `JKITE_ASSUME_YES` | set to `1`, `true` or `yes` to answer yes in advance, like `--yes` |
| `JKITE_DIR` | base directory (default `~/.jkite`) |
| `JKITE_CACHE_DIR` | cache directory (default `$JKITE_DIR/cache`) |
| `JKITE_MAVEN_REPO` | local Maven repository to use instead of jkite's own; set it to `~/.m2/repository` to share the machine's |
| `JKITE_DEFAULT_JAVA_VERSION` | JDK to use when a script names none (default 17) |
| `JKITE_JDK_INDEX` | read the JVM index from here instead of from Maven Central |
| `JKITE_JAVA_OPTIONS` | JVM options for jkite itself |
| `JKITE_DOWNLOAD_RETRY` | extra download attempts (default 5, `0` disables retries) |
| `JKITE_DOWNLOAD_RETRY_DELAY` | seconds between attempts (default `0`, meaning exponential backoff) |
| `JKITE_LOCK_TIMEOUT` | seconds to wait for another run that is downloading (default 600) |
| `JKITE_DIST_URL` | fetch `jkite.jar` from here instead of from the pinned URL |
| `JKITE_REPO`, `JKITE_REF` | the repository and release the installer installs from |
| `JKITE_DIST_BASEURL` | install from here instead of from a GitHub release |

Everything jkite writes goes under `JKITE_DIR`; nothing is written into
the project. Several runs at once are safe: each download is taken by one run
while the others wait, and every file is renamed into place only once it is
complete, so a build matrix never trips over a half-written file.

That includes the dependency jars, which go into a local Maven repository of
jkite's own at `$JKITE_CACHE_DIR/deps` rather than into `~/.m2/repository`.
Maven Resolver would use `~/.m2` by default, and everywhere else in jkite what
reaches the class path is pinned by a SHA-256 the project commits — so taking
a jar out of a directory that any other build on the machine can write to, and
that jkite neither pins nor owns, was the one place where what ran depended on
the machine rather than on the project. It also meant emptying `JKITE_DIR` did
not give you a cold machine, which is what `--clear-cache` is for.

The cost is that a machine which already has an artifact in `~/.m2` fetches it
again, and `--clear-cache` now removes the jars as well as the resolved class
paths. `JKITE_MAVEN_REPO=~/.m2/repository` buys the sharing back, and jkite
then leaves that directory alone when clearing the cache. Only the directory of
files moves either way: mirrors, proxies and credentials are still read from
`~/.m2/settings.xml`, since those describe the machine's route to a repository
and not which bytes come back. A `<localRepository>` set in that file is
overridden, as it is by Maven's own `-Dmaven.repo.local`.

## Behind a proxy

`http_proxy`, `https_proxy` and `no_proxy` are enough. The launcher fetches
with curl or wget, which read them; the jar reads none of them by itself, so
jkite translates them into the JVM's own settings before it connects. A
`-Dhttps.proxyHost` passed in `JKITE_JAVA_OPTIONS` wins over the environment.

Dependencies are the exception: they are fetched by Maven Resolver, which is
configured the way Maven is, in the `<proxies>` section of
`~/.m2/settings.xml`.

If the proxy terminates TLS and re-signs with a company CA, note which trust
store each half uses. curl uses the operating system's, so the launcher works
as soon as the CA is installed there. Java does not: it uses the `cacerts` of
the JDK that is running, and a JDK jkite downloaded is a stock Temurin whose
`cacerts` has never heard of your company. The symptom is a launcher that
succeeds and then a `PKIX path building failed` from the jar. Either point
jkite at a JDK that has the CA —

```bash
export JAVA_HOME=/path/to/a/jdk/with/the/ca
```

— or give the jar the trust store directly:

```bash
export JKITE_JAVA_OPTIONS="-Djavax.net.ssl.trustStore=/path/to/truststore.p12 \
                           -Djavax.net.ssl.trustStorePassword=..."
```

## Downloads ask first

jkite fetches four kinds of thing: its own jar, a JDK to run that jar with, the
JDK a script asks for with `//JAVA`, and the dependencies a script declares.
When something has to be fetched it says what, and on a terminal it asks.

```
jkite has to download:
  - jkite.jar 0.2.0
  - a JDK to run it with (Temurin 25.0.3); this machine has none

Continue? [Y/n]:
```

Only a download that would really happen is asked about, so this is a first-run
question rather than a per-run one. A JDK that is already installed never
reaches it, and neither does a dependency already in the local Maven repository.
A cold first run asks at least twice — the launcher about its jar and the JDK to
start it with, the jar about the dependencies — and a third time when the script
names a `//JAVA` the machine does not have. `--update` asks before replacing an
installation.

| | |
| --- | --- |
| `JKITE_CONFIRM_DOWNLOADS=auto` (default) | ask on a terminal; otherwise say what is being fetched and go ahead, so an unattended build never waits for an answer nobody is there to give |
| `JKITE_CONFIRM_DOWNLOADS=always` | ask, and fetch nothing when there is no terminal. The setting for a machine meant to stay off the network |
| `JKITE_CONFIRM_DOWNLOADS=never`, `JKITE_ASSUME_YES=1`, `--yes` | never ask |

Enter accepts. The question and its answer go to the terminal, never to stdout,
so a pipeline built on a tool's output is unaffected.

"On a terminal" means something slightly different on each launcher, and the
difference shows only when stdin is redirected and nothing else is. The POSIX
launcher opens the terminal device itself, so `jkite Tool.java < data.txt` at a
terminal is still asked. `jkite.cmd` decides from stdin, so the same command
counts as having no terminal: with `auto` it says what it is fetching and goes
ahead, and with `always` it stops. Neither downloads anything unannounced,
which is what the setting is for; pass `--yes` if you meant to allow it.

```yaml
- run: jkite/jkite tools/Report.java
  env:
    JKITE_CONFIRM_DOWNLOADS: never
```

## Paths

A project can live where your projects live, with one Windows caveat below.
Non-ASCII directory names work on Linux and macOS — Japanese, Cyrillic and
accented Latin are all tested — and on Windows they work as long as the whole
path is inside the machine's code page. Paths past Windows' 260-character limit
work everywhere.

Windows needed work for that, and the reason is worth knowing if you hit
something like it elsewhere. `java.exe` converts its own command line to the
machine's ANSI code page, so a jar under a path that page cannot hold arrives
as question marks and Java says `Unable to access jarfile`. It is not the
console's code page, so `chcp` does not help. `jkite.cmd` hands Java the 8.3
short name of its jar, which is ASCII whatever the directory is called.

The same conversion applies to every JDK tool jkite then starts, and on Windows
it bites at the compile rather than the run: `javac` is handed the source file
and a build directory, and a path outside the code page arrives as question
marks, which is not something a Windows path may contain.

**On Windows, keep the whole path inside your code page** — the script, every
directory above it, and `JKITE_DIR`. Not just the filename: jkite hands javac
the absolute path of the source, so every directory between the drive and the
file is on that command line, and one Japanese folder is enough. A Japanese
name on a Japanese Windows is inside the code page and works; it is the mixed
case that does not, such as anything Japanese under an English install. jkite
checks before it starts `javac` and stops with a message naming the path and
the code page, rather than letting `javac` say `Invalid filename`.

If renaming the directory is not an option, **give it an ASCII path with
`subst`**:

```
subst X: "C:\Users\name\プロジェクト"
X:
jkite Report.java
```

Work through `X:` and nothing outside the code page ever reaches `javac`. It
needs no administrator rights and changes nothing about the machine — only
this session, and `subst X: /d` undoes it.

A junction (`mklink /J`) does **not** work for this, though it looks like it
should: a junction is a reparse point, so jkite resolves it back to the real
directory (see below) and the Japanese name comes back. A subst drive is a
drive-letter mapping rather than a reparse point, so it survives. Both are
measured on the Windows CI job rather than assumed.

jkite no longer adds to that list: the build directory used to be named after
the script, so `レポート.java` put its own name into a path of jkite's making as
well. It does not any more. A name that already worked is used exactly as it
is, so nothing is renamed and nothing is rebuilt for it.

Paths are resolved the way the file system resolves them, not by folding the
text. If a directory on the way is a symbolic link and the path has a `..`
after it, `a/link/../x` is not `a/x` — the link is followed first — so jkite
asks the file system rather than cancelling the two against each other. It
runs the file your shell would open. A file that is not there is reported as
missing, naming which one it was — the script, a `//SOURCES` sibling or a
`//FILES` resource.

One more, and it is the JDK's rather than jkite's: a character outside the Basic
Multilingual Plane — in practice an emoji — anywhere in the path `jkite/` is
installed under. `java -jar` then fails before anything of jkite runs, with
`Error decoding percent encoded characters`. An emoji elsewhere is fine.

## Requirements

A machine needs `bash` with `curl` or `wget`, `sha256sum` or `shasum`, and
`tar` with `gzip`; or, on Windows, nothing that Windows does not already ship.
A JDK 11 or newer is used if there is one, and installed if there is not.

`bash` rather than any POSIX shell: the launchers use `[[`, `local` and
`BASH_SOURCE`, so `sh`, `dash` and `ash` will not run them. macOS and every
usual Linux ship it; a minimal container may not. Alpine needs two things of
its own - `bash`, and a JDK installed by hand, since the JDKs jkite downloads
are built against glibc. jkite stops there and says so rather than fetching
one; `JKITE_JDK_INDEX` pointed at an index of musl builds is the way round it.

## How it works, and contributing

What happens between `git clone` and the tool's first line of output, in three
diagrams: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). How jkite is built,
released and kept in step with JBang: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
What is welcome and what is not, and under which license a contribution
arrives: [CONTRIBUTING.md](CONTRIBUTING.md).

Found a security problem? Report it privately, not in an issue:
[SECURITY.md](SECURITY.md).

## License

MIT License, Copyright (c) 2020 Max Rydahl Andersen (the original JBang notice
is kept unchanged in [LICENSE](LICENSE)); the jkite modifications are
provided under the same license, and so is every contribution made to it. `jkite.jar` bundles MIMA (EPL-2.0), Apache
Maven Resolver, Apache HttpClient, Apache Commons Compress and Gson
(Apache-2.0) and SLF4J (MIT); see [THIRD-PARTY.md](THIRD-PARTY.md) for details
and for the origin of code adapted from other projects.
