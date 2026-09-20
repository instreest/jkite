# Third-party notices

jkite is a reduced fork of [JBang](https://github.com/jbangdev/jbang),
Copyright (c) 2020 Max Rydahl Andersen, distributed under the MIT License
(see [LICENSE](LICENSE)). The original copyright and permission notice is kept
unchanged; the modifications made for jkite are contributed under the same
MIT License.

jkite is not affiliated with, endorsed by, or supported by the JBang
project. Its name is its own; where the code comes from is said here.

## Code derived from other projects

| Files | Origin | License |
| --- | --- | --- |
| the files listed in `misc/upstream-mirror.txt` (among them `Directives.java`, `MavenCoordinate.java`, `JitPackUtil.java` and their test) | [jbangdev/jbang](https://github.com/jbangdev/jbang), Copyright (c) 2020 Max Rydahl Andersen, copied unchanged | MIT |
| the files listed in `misc/upstream-shims.txt`, and the parts of `src/main/java/io/github/instreest/jkite/*` and `src/main/scripts/*` that are derived from upstream | [jbangdev/jbang](https://github.com/jbangdev/jbang), Copyright (c) 2020 Max Rydahl Andersen, derived | MIT |
| `Jdk.java`, `JdkManager.java`, the root-folder stripping and `Contents/Home` selection of `Unpacker.java`, parts of `Util.java` (OS/architecture detection, link handling) | [jbangdev/jbang-devkitman](https://github.com/jbangdev/jbang-devkitman), Copyright (c) Max Rydahl Andersen and contributors | MIT |
| `OsDetector.java` (OS and architecture normalisation tables) | [os-maven-plugin](https://github.com/trustin/os-maven-plugin) by Trustin Lee, as also used by the Nisse os-detector in JBang. The file keeps its original copyright and licence notice and says what was changed, as the licence asks | Apache License 2.0 |

`Placeholders.java` expands the same `${...}` syntax as the
`PropertiesValueResolver` JBang carries, but is jkite's own code: that file
reached JBang from the JBoss projects with a licence history we could not
establish, so it was reimplemented rather than mirrored.

## Data downloaded at runtime

The list of downloadable JDKs comes from the JVM index published by the
[Coursier](https://github.com/coursier/jvm-index) project (Apache License 2.0)
as `io.get-coursier.jvm.indices:index-<platform>` on Maven Central. Only the
index is retrieved from there; the JDK archives themselves are downloaded from
each distributor's own site, and the license of the JDK that gets installed is
the one of that distribution (Eclipse Temurin, the default, is GPLv2 with the
Classpath Exception).

## Libraries bundled in `jkite.jar`

| Library | License |
| --- | --- |
| [MIMA](https://github.com/maveniverse/mima) (`eu.maveniverse.maven.mima:*`) | Eclipse Public License 2.0 |
| [Apache Maven Resolver](https://maven.apache.org/resolver/) including its HTTP transport, and Apache Maven model/settings builders (`org.apache.maven.resolver:*`, `org.apache.maven:*`) | Apache License 2.0 |
| The Plexus components those pull in (`org.codehaus.plexus:plexus-utils`, `plexus-interpolation`, `plexus-cipher`, `plexus-sec-dispatcher`) | Apache License 2.0, except for three parts of `plexus-utils` — see below |
| [Apache HttpClient / HttpCore](https://hc.apache.org/) (`org.apache.httpcomponents:*`) and the Mozilla public suffix list it carries, pulled in by that transport | Apache License 2.0 / MPL-2.0 for the list |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/), with Commons IO, Lang and Codec (`org.apache.commons:*`, `commons-io:*`, `commons-codec:*`) | Apache License 2.0 |
| [Gson](https://github.com/google/gson) (`com.google.code.gson:gson`) | Apache License 2.0 |
| [ASM](https://asm.ow2.io/) (`org.ow2.asm:asm`, needed by the Maven model builder) | BSD 3-Clause |
| [SLF4J](https://www.slf4j.org/) (`slf4j-api`, `slf4j-nop`, `jcl-over-slf4j`) | MIT License |
| [JSpecify](https://jspecify.dev/) (`org.jspecify:jspecify`), the nullness annotations the mirrored files use | Apache License 2.0 |
| [Error Prone annotations](https://errorprone.info/) (`com.google.errorprone:error_prone_annotations`), pulled in by Gson | Apache License 2.0 |

Maven artifacts are fetched with Maven Resolver's own HTTP transport, the one
Maven itself uses, so its checksum, retry, redirect and authentication
behaviour is the ecosystem's rather than this fork's. JDK archives and the
JVM index are fetched by `jdk/Downloader.java` on `java.net.HttpURLConnection`,
since those come from the distributors' own sites rather than from a Maven
repository.

`jkite.jar` carries the notices of everything it bundles:

| In the jar | |
| --- | --- |
| `META-INF/NOTICE` | the NOTICE files of all bundled Apache-2.0 artifacts, merged |
| `META-INF/licenses/Apache-2.0.txt` | Apache License 2.0, for Maven Resolver, Apache Maven, HttpClient, Commons, Gson, JSpecify and the Error Prone annotations, and for the os-maven-plugin code `OsDetector.java` is derived from |
| `META-INF/licenses/MIT-slf4j.txt` | SLF4J |
| `META-INF/licenses/BSD-3-Clause-asm.txt` | ASM |
| `META-INF/licenses/EPL-2.0.txt` | MIMA |
| `META-INF/licenses/MPL-2.0.txt` | the public suffix list carried by Apache HttpClient |
| `META-INF/LICENSE-jkite.txt` | JBang, and this fork |
| `licenses/extreme.indiana.edu.license.TXT`, `licenses/thoughtworks.TXT`, `licenses/javolution.license.TXT` | the three inside `plexus-utils`, carried at the paths that artifact uses |
| `META-INF/THIRD-PARTY.md` | this file |

Neither the Eclipse Public License 2.0 (MIMA) nor the Mozilla Public License
2.0 (the public suffix list) is shipped inside the artifact it covers, so those
two texts are kept in `misc/licenses/` in this repository: the EPL as published
at eclipse.org, the MPL as published in the
[public suffix list's own repository](https://github.com/publicsuffix/list).

MIMA is distributed under the Eclipse Public License 2.0, which asks that
recipients be told where to get the source: it is at
<https://github.com/maveniverse/mima>, and every released version is on Maven
Central with its `-sources` jar.

### The three licenses inside `plexus-utils`

`plexus-utils` is an Apache-2.0 artifact whose own `NOTICE` and `licenses/`
directory say that some of its files are not. All of them are in `jkite.jar`,
because a shaded jar takes the classes rather than the packages they came in.
They are listed here because a table row reading "Apache License 2.0" is not
true of them, and because two of the three ask for an acknowledgement that a
license file alone does not give.

| Files | Terms |
| --- | --- |
| `org.codehaus.plexus.util.xml.pull.MXParser`, `XmlPullParser`, `XmlPullParserException` — the XML pull parser the Maven model builder reads POMs with | Indiana University Extreme! Lab Software License 1.1.1. A BSD-style license whose clause 3 asks that redistributions acknowledge the Extreme! Lab; `META-INF/NOTICE` in `jkite.jar` does, and so does this paragraph |
| `org.codehaus.plexus.util.cli.Commandline`, `StreamPumper`, `StreamConsumer` | The CruiseControl license, Copyright (c) 2001-2003 ThoughtWorks, Inc.: BSD 3-Clause in substance |
| `org.codehaus.plexus.util.FastMap` | Declared public domain by its author (J.A.D.E., later Javolution). `licenses/javolution.license.TXT` travels with it and is kept |

Nothing here restricts redistribution beyond what MIT already allows, and
nothing here is copyleft. What they ask for is attribution, which is why they
are written down rather than summarised away.

The exact list of bundled artifacts can be printed with
`./gradlew dependencies --configuration runtimeClasspath`.
