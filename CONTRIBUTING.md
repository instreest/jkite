# Contributing to jkite

## Licensing of contributions

jkite is distributed under the MIT License (see [LICENSE](LICENSE)). **When you
send a pull request, an issue with a patch in it, or any other contribution,
you offer it under that same license**, and you confirm that it is yours to
offer — that you wrote it, or that it comes from somewhere the MIT License can
take it from.

There is nothing to sign. This paragraph is the whole of it: what comes in
carries the same license as what goes out, so a contribution can be released
without asking anyone again.

If a change brings in code from another project, say so in the pull request and
name the project and its license, so that
[THIRD-PARTY.md](THIRD-PARTY.md) can be kept true. Code under a license that
cannot be redistributed under MIT cannot be taken.

### Why it stays MIT

MIT permits redistribution under other terms, so this fork could put its own
code under a different license — Apache-2.0, say, for its patent grant — while
keeping JBang's notice on the files that are JBang's. It deliberately does not,
and the reason is the direction code travels.

Compatibility here runs one way. MIT code can go into an Apache-2.0 project,
but not back: JBang is MIT, and it could not take a fix from an Apache-2.0
jkite and keep distributing it under MIT. The shims in `dev.jbang.*` are
written against upstream's API, and this file asks contributors to send a fix
to JBang when the fix belongs there. Relicensing would close the return path
that the mirror, the shims and `misc/sync-upstream.sh` exist to keep open —
this project would be dismantling its own bridge to upstream.

Matching some other project's license is not a reason to move. A tool jkite
runs is a separate program in a separate process; nothing is combined, so
nothing has to agree.

## Reporting a security problem

Not here. See [SECURITY.md](SECURITY.md) — a vulnerability goes in a private
advisory, not a public issue.

## What this project is for

jkite exists so that **a developer can hand a Java command-line tool to
someone else, and neither of them has to think about JDKs, dependencies or
builds.** Whoever checks the project out runs one command.

That purpose is what decides whether a change belongs here. jkite is a
*reduced* fork of [JBang](https://github.com/jbangdev/jbang): almost everything
it does not do, it deliberately does not do. A pull request that adds a feature
is a pull request that argues the purpose above needs it, so make that argument
in the description — the code is the easy part to review.

Things that are welcome without any such argument:

- a bug: something documented that does not behave as documented
- a test for something not covered
- a platform jkite claims to support and does not work on
- documentation that is wrong, or that explains what rather than why

Things that will probably be declined:

- a subcommand, or an option that lets whoever *runs* a tool override what the
  tool's author declared. The author decides what the tool needs; that is the
  point of committing the directives
- support for another JDK distribution, another dependency resolver, another
  language
- a feature that exists in JBang and was removed here. Ask first — there is
  usually a reason, and if there is not, it is worth hearing

If you are not sure, open an issue before writing the code. Nobody enjoys
declining a finished patch.

## Working on the code

Everything about building, testing, releasing and syncing with upstream is in
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md); how a run actually works is in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Two things from there matter
before the first edit:

**Do not edit the mirrored files.** The tree below the directive-parser
interface is in three parts, and where a change goes depends on which part it
lands in:

| | Where | What a change there means |
| --- | --- | --- |
| Mirror (`misc/upstream-mirror.txt`) | `dev.jbang.*` | copied from JBang byte for byte. An edit here is undone by the next sync. Fix it in JBang instead |
| Shims (`misc/upstream-shims.txt`) | `dev.jbang.*` | upstream's API, reduced implementation. Keep the signatures; the behaviour has to stay the one upstream's callers expect |
| jkite's own | `io.github.instreest.jkite.*` | where changes belong |

So if the fix is in `Directives.java`, the pull request that fixes it goes to
JBang, and jkite picks it up with `misc/sync-upstream.sh`. Everyone gets it
that way, and it survives.

CI checks this rather than trusting it: `misc/sync-upstream.sh --check`
compares every mirrored file with upstream and fails if one has been edited
here. Run it yourself if you are not sure which part of the tree you are in.

**Run the build before sending.**

```bash
./gradlew build
```

This runs the tests as well. CI runs the same command on Linux, macOS and
Windows, and then runs the shipped jar on Java 11 and 25. The Windows job is
not a formality: the launcher exists twice, once for `bash` and once for
`cmd.exe`, and the `cmd.exe` half cannot run anywhere else. The macOS job is
not either: the POSIX scripts take a different path there, and its userland is
BSD's.
If you touch `src/main/scripts/`, expect the Windows job to be the one that has
an opinion, and remember that `dist/` carries a copy of those scripts that has
to stay in step.

## Style

Match the code around you — that is the whole rule, and it is a real one here
because the tree has three provenances and they are meant to stay
distinguishable.

Comments say **why**, not what. A comment that repeats the line below it is
noise; a comment explaining the Windows behaviour that forced the line above it
is the reason the next person does not undo it.

Commit messages: a short first line saying what changed, then prose saying why.
If a test was hard to get right, or a mistake was worth recording, the commit
message is where it goes.
