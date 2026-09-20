# jkite

This directory lets you run this project's `.java` tools without installing
anything first — not even a JDK.

```bash
jkite/jkite path/to/Tool.java      # macOS, Linux, WSL
jkite\jkite.cmd path\to\Tool.java  # Windows
```

Everything here is committed with the project, the way a Gradle or Maven
wrapper is. It is [jkite](https://github.com/instreest/jkite), a
reduced fork of [JBang](https://github.com/jbangdev/jbang), not affiliated with
the JBang project.

## The first run

The launcher downloads what the machine is missing and checks each download
against a SHA-256 committed in `jkite.properties`:

1. `jkite.jar`, into `~/.jkite/cache/jkite/<version>`.
2. A JDK, into `~/.jkite/cache/jdks/bootstrap`, but only if the machine has
   no usable one. `JAVA_HOME` and a `javac` on the `PATH` are used when they are
   Java 11 or newer.

Those two are the launcher's, and they are the two this project pins. The jar
then fetches what the tool itself asks for — the JDK named by `//JAVA`, and the
`//DEPS` from Maven Central — and those are verified against the checksums their
publishers serve, not against anything in `jkite.properties`.

Nothing is written into the project, and the caches are per machine, so other
projects pinning the same version download nothing. Several runs at once are
safe: one run downloads while the others wait.

## Files

| File | |
| --- | --- |
| `jkite`, `jkite.cmd` | the launchers |
| `jkite.properties` | which jkite and which JDK this project uses: version, URL and SHA-256 |
| `jkite-bootstrap-jar`, `.cmd` | download and verify `jkite.jar` |
| `jkite-bootstrap-jdk`, `.cmd` | download and verify a JDK when the machine has none |
| `install.sh`, `install.cmd` | install and update this directory |
| `LICENSE`, `README.md` | |

`jkite.jar` is not here on purpose: it is a download, so this project's
history carries about 98 kB of scripts rather than a binary per update. To
pin it into the project anyway, put a `jkite.jar` in this directory; the
launcher prefers it.

## Which version is this

```bash
jkite/jkite --version
```

names the version that will run, and downloads nothing.

## Updating

Normally you do not: the version is committed, so `git pull` brings whatever
this project's maintainer chose. To change it yourself:

```bash
jkite/jkite --update            # the newest release
jkite/jkite --update v0.3.0     # or a particular one
```

`jkite\jkite.cmd --update` does the same on Windows. It replaces every
file here and needs neither the jar nor a JDK. Commit the result, and bear in
mind that it makes this project run a jkite its maintainer has not tried.

`JKITE_DIST_URL` points the jar download at a mirror for one run, for a
machine that cannot reach GitHub releases.
