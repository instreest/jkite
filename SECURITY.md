# Security policy

## Reporting a vulnerability

Please report it privately, through GitHub: on the
[Security tab](https://github.com/instreest/jkite/security/advisories/new),
choose **Report a vulnerability**. That opens a private advisory that only you
and the maintainer can see.

Please do not open a public issue for it. jkite installs and runs software on
other people's machines, so a problem that is public before it is fixed is a
problem in every checkout that has jkite committed into it.

What helps most, in whatever detail you have:

- what an attacker would have to control already (a network position, a Maven
  repository, a file in the project, an environment variable)
- what they would get out of it
- the shortest way to see it happen

There is one maintainer and this is not anyone's day job. Expect an
acknowledgement within a week. If a week passes with nothing, assume the
message did not arrive and say so publicly without details — "I reported
something privately and heard nothing" is safe to post.

Only the latest release is fixed. jkite is small and installing a new version
is a one-line change in a committed properties file, so there are no branches
to back-port to.

You will be credited in the advisory and the release notes unless you would
rather not be.

## What jkite is responsible for

jkite's job is that **what runs is what the project asked for**. A project
commits a `jkite/` directory and a script; whoever checks it out runs it. So
the question a report should answer is whether jkite can be made to fetch,
build or run something the project did not ask for.

### In scope

- Getting past a SHA-256 check: on `jkite.jar`, or on the bootstrap JDK the
  launcher installs. `JKITE_DIST_URL` moves where the jar is fetched from, but
  the checksum in `jkite.properties` still has to match, and a way around that
  is a vulnerability.
- Getting a JDK installed from somewhere other than the distribution's own
  account, or past the checksum published beside it.
- Escaping the directory an archive is unpacked into, whether through an entry
  name or a link inside it, and the same through `//FILES`.
- Anything fetched over plain http, or a redirect off https that is followed.
- Getting past the confirmation that is asked before a download, or past
  `--offline`.
- One project's run affecting another's: a cache entry that can be poisoned,
  or a lock that can be used to make another run install the wrong thing.
- Anything jkite writes outside `JKITE_DIR`, or into the project.
- A script's directives reaching further than they should — `${...}` expansion
  or an option that lets a directive do something the table in the README does
  not describe.

### Not in scope

- **A project whose script is hostile.** Running the tools of a project you
  checked out means running that project's code, exactly as `./gradlew` or
  `npm run` does. jkite is the thing that runs it, not a sandbox around it,
  and it has never claimed to be one.
- **What a project's own `//DEPS` pulls in.** The dependency a project names is
  the dependency it gets. Whether that artifact is trustworthy is between the
  project and the repository it names.
- **Vulnerabilities in the JDK jkite installs, or in the libraries bundled in
  `jkite.jar`** (they are listed in [THIRD-PARTY.md](THIRD-PARTY.md)). Those
  belong to their own projects. Do tell us anyway, so the version can be moved
  — that part is ours.
- **Anything that needs a position you would already have.** Being able to
  write to the user's home directory, or to break TLS, is not a step towards
  something: it is already past everything.
- Missing hardening with nothing behind it. "This could use X" is welcome as a
  normal issue; it is not an advisory.

### Where the code comes from

jkite is a reduced fork of [JBang](https://github.com/jbangdev/jbang), and some
files are copied from it unchanged (`misc/upstream-mirror.txt` lists them).
Report anything you find in jkite here, whichever file it is in. Working out
whether the same problem exists upstream, telling them if it does, and deciding
what jkite does in the meantime, is this project's job and not the reporter's:
what ships to jkite's users is jkite's to answer for, wherever the line was
written.

jkite is not affiliated with, endorsed by, or supported by the JBang project,
so a report sent only to them is not a report to us.
