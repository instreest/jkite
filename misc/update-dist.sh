#!/usr/bin/env bash
#
# Refreshes dist/, which is jkite as a project installs it (see
# dist/install.sh): the launcher scripts and LICENSE are copied there from the
# sources, and jkite.properties is written to pin both downloads a project
# can need - jkite.jar and, for a machine with no Java at all, a JDK to
# start it with. install.sh, install.cmd and README.md are maintained in dist/
# itself.
#
# Neither download is decided at run time. The jar is a release asset and the
# JDK is Eclipse Temurin, and the version, URL and SHA-256 of each is resolved
# here, once, and committed with the project. So a project's history carries
# about 98 kB of scripts instead of a binary, the launcher scripts have nothing
# to parse but a properties file, and what a checkout installs is the same
# thing every time.
#
#   misc/update-dist.sh <version>     build the jar, refresh dist/ for it
#   misc/update-dist.sh --check       report whether dist/ is up to date
#
# Publish dist/ and the jar as the assets of the release tagged v<version> -
# install.sh fetches the scripts from there too - then commit dist/:
#
#   misc/update-dist.sh 0.2.0
#   gh release create v0.2.0 dist/* build/libs/jkite.jar
#   git add dist && git commit -m 'Release 0.2.0'
#
# --check rebuilds nothing. It compares the copied scripts, checks that both
# installers list every file in dist/, and checks that the three lines pinning
# the jar still describe one artifact. The jar's SHA-256 it cannot verify -
# that is the checksum of a release asset, not of anything in the checkout.
#
# Needs curl, unzip and awk on top of what the build needs.
#
# Environment:
#   JKITE_REPO             the GitHub repository releases are published to
#                              (default instreest/jkite)
#   JKITE_RELEASE_BASEURL  where releases are served from
#                              (default https://github.com)
#   JKITE_JVM_INDEX_BASEURL  a mirror of Maven Central for the JVM index
#
set -eu
cd "$(dirname "$0")/.."

copied="src/main/scripts/jkite src/main/scripts/jkite.cmd
        src/main/scripts/jkite-bootstrap-jdk src/main/scripts/jkite-bootstrap-jdk.cmd
        src/main/scripts/jkite-bootstrap-jar src/main/scripts/jkite-bootstrap-jar.cmd
        LICENSE"

repo=${JKITE_REPO:-instreest/jkite}
releaseBaseUrl=${JKITE_RELEASE_BASEURL:-https://github.com}
jvmIndexBaseUrl=${JKITE_JVM_INDEX_BASEURL:-https://repo1.maven.org/maven2}

# The JDK the launchers install when the machine has none. It only has to run
# jkite.jar; the JDK a script asks for with //JAVA is installed by the jar.
bootstrapJavaVersion=25

# The platforms a project may be checked out on. Named as the JVM index names
# them, which is also what the launcher scripts compute from uname. "a:b" pins
# platform a from b's entry, for a platform Temurin does not build: Windows on
# ARM runs the x64 build under emulation, and this JDK only has to start
# jkite.jar. Drop the mapping once Temurin publishes windows-arm64.
platforms="linux-amd64 linux-arm64 darwin-amd64 darwin-arm64 windows-amd64 windows-arm64:windows-amd64"

# The files install.sh and install.cmd fetch, as each of them lists them. They
# name every file by hand because at the moment they run there is no checkout
# to list and no directory index to read: they are fetched one at a time from a
# release. So a file added to or removed from dist/ has to be added to or
# removed from two lists, in two languages, and nothing says when it was not -
# the installer just quietly installs an incomplete set, and the failure lands
# on whoever runs the tool rather than on whoever changed dist/.
installer_list() {  # $1 = the installer to read the list out of
  case $1 in
    # the trailing \r: .cmd files are checked out with CRLF (.gitattributes),
    # so the line does not end at the closing quote
    *.cmd) tr -d '\r' < "$1" | sed -n 's/^set "files=\(.*\)"$/\1/p' ;;
    *)     awk '/^[[:space:]]*files="/ { f = 1 }
                f { line = $0
                    sub(/^[[:space:]]*files="/, "", line)
                    # the closing quote has to be looked for before it is
                    # stripped, or the list runs on to the end of the file
                    last = (line ~ /"/)
                    sub(/".*$/, "", line)
                    print line
                    if (last) { exit }
                  }' "$1" ;;
  esac | tr ' \t\n' '\n\n\n' | grep -v '^$' | sort
}

# The three lines that pin the jar have to describe one artifact.
#
# They did not once. A commit renaming the project from jbanglite to jkite
# hand-edited distributionUrl - the release, and the jar's own filename, both
# changed - and left distributionSha256Sum alone, so the committed pin was the
# checksum of a jar that no longer existed under a name nothing served. Every
# install at that version would have failed the verification jkite exists to
# do, and nothing said so, because the SHA-256 of an artifact that has not been
# built yet is not something a checkout can recompute.
#
# What a checkout CAN say is that the three lines still agree with each other:
# the URL has to name this repository, the version in the tag has to be
# distributionVersion, and the file at the end has to be jkite.jar. That is
# exactly what a hand edit breaks and what update-dist.sh always gets right, so
# it turns "somebody edited this by hand" into a failing check rather than a
# failing install.
check_pin() {
  properties=dist/jkite.properties
  pinnedVersion=$(sed -n 's/^distributionVersion=//p' "$properties")
  pinnedUrl=$(sed -n 's/^distributionUrl=//p' "$properties")
  pinnedSha=$(sed -n 's/^distributionSha256Sum=//p' "$properties")
  expectedUrl="$releaseBaseUrl/$repo/releases/download/v$pinnedVersion/jkite.jar"

  if [ -z "$pinnedVersion" ] || [ -z "$pinnedUrl" ] || [ -z "$pinnedSha" ]; then
    echo "$properties does not pin the jar: version, URL and SHA-256 must all be set" 1>&2
    return 1
  fi
  if [ "$pinnedUrl" != "$expectedUrl" ]; then
    echo "$properties pins a URL that does not match its own version:" 1>&2
    echo "  distributionUrl:     $pinnedUrl" 1>&2
    echo "  from the version:    $expectedUrl" 1>&2
    echo "  (run misc/update-dist.sh <version> rather than editing by hand)" 1>&2
    return 1
  fi
  case $pinnedSha in
    [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]*)
      [ ${#pinnedSha} -eq 64 ] || {
        echo "$properties: distributionSha256Sum is not a SHA-256: $pinnedSha" 1>&2
        return 1
      } ;;
    *)
      echo "$properties: distributionSha256Sum is not a SHA-256: $pinnedSha" 1>&2
      return 1 ;;
  esac
}

check_installers() {
  present=$(ls dist | sort)
  bad=
  for installer in dist/install.sh dist/install.cmd; do
    listed=$(installer_list "$installer")
    if [ "$listed" != "$present" ]; then
      echo "$(basename "$installer") does not list what dist/ holds:" 1>&2
      diff <(echo "$present") <(echo "$listed") \
        | sed -e 's/^</  only in dist\/:     /' -e 's/^>/  only in the list: /' \
        | grep -v '^[0-9-]' 1>&2
      bad=1
    fi
  done
  [ -z "$bad" ]
}

if [ "${1:-}" = "--check" ]; then
  stale=
  for from in $copied; do
    cmp -s "$from" "dist/$(basename "$from")" || stale="$stale $(basename "$from")"
  done
  failed=
  if [ -n "$stale" ]; then
    echo "dist/ is out of date:$stale (run misc/update-dist.sh <version>)" 1>&2
    failed=1
  fi
  check_installers || failed=1
  check_pin || failed=1
  if [ -n "$failed" ]; then
    exit 1
  fi
  echo "dist/ is up to date with the launcher scripts and LICENSE," 1>&2
  echo "both installers list every file in it, and the pinned jar's URL and" 1>&2
  echo "version agree" 1>&2
  echo "(the jar's SHA-256 is of a build and is not recomputed here)" 1>&2
  exit 0
fi

version=${1:-}
if [ -z "$version" ]; then
  echo "Usage: misc/update-dist.sh <version> | --check" 1>&2
  exit 2
fi

work=$(mktemp -d "${TMPDIR:-/tmp}/jkite-dist.XXXXXX")
trap 'rm -rf "$work"' EXIT

sha256_of() {
  if command -v sha256sum > /dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

# Prints "<version> <archive type> <url>" of the newest Temurin
# $bootstrapJavaVersion the JVM index lists for the platform $1.
jvm_index_entry() {
  local plat=$1 base metaVersion
  base="$jvmIndexBaseUrl/io/get-coursier/jvm/indices/index-$plat"
  curl -fsSL "$base/maven-metadata.xml" -o "$work/meta.xml"
  metaVersion=$(grep -o '<release>[^<]*' "$work/meta.xml" | head -1 | cut -d'>' -f2)
  [ -n "$metaVersion" ] || { echo "No released JVM index for $plat" 1>&2; return 1; }
  curl -fsSL "$base/$metaVersion/index-$plat-$metaVersion.jar" -o "$work/index.jar"
  unzip -p "$work/index.jar" "coursier/jvm/indices/v1/$plat.json" > "$work/index.json"
  # the index lists, per distribution, a version and a "<type>+<url>" value
  awk -v want="$bootstrapJavaVersion" '
    /^[[:space:]]*"temurin"[[:space:]]*:[[:space:]]*\{/ { inside = 1; next }
    inside && /^[[:space:]]*\}/ { exit }
    inside && match($0, /"[0-9][^"]*"[[:space:]]*:[[:space:]]*"[a-z]+\+[^"]+"/) {
      split(substr($0, RSTART, RLENGTH), field, "\"")
      if (field[2] == want || index(field[2], want ".") == 1) {
        plus = index(field[4], "+")
        print field[2] " " substr(field[4], 1, plus - 1) " " substr(field[4], plus + 1)
      }
    }
  ' "$work/index.json" | sort -V | tail -1
}

echo "Resolving Temurin $bootstrapJavaVersion for: $platforms" 1>&2
jdkProperties=$work/jdk.properties
: > "$jdkProperties"
jdkVersion=
for spec in $platforms; do
  plat=${spec%%:*}
  from=${spec#*:}
  entry=$(jvm_index_entry "$from")
  [ -n "$entry" ] || { echo "No Temurin $bootstrapJavaVersion for $from in the JVM index" 1>&2; exit 1; }
  set -- $entry
  jdkVersion=$1
  type=$2
  url=$3
  # The launchers unpack without asking what the archive is: a .tar.gz on
  # POSIX, a .zip through the tar Windows ships with. Refuse to pin anything
  # else rather than let a run find out.
  case "$plat:$type" in
    windows-*:zip|linux-*:tgz|darwin-*:tgz) ;;
    *) echo "Unexpected archive type '$type' for $plat; the launchers cannot unpack it" 1>&2; exit 1;;
  esac
  curl -fsSL "$url.sha256.txt" -o "$work/jdk.sha256" \
    || { echo "Temurin publishes no SHA-256 next to $url" 1>&2; exit 1; }
  sha=$(cut -d' ' -f1 < "$work/jdk.sha256")
  [ -n "$sha" ] || { echo "Empty SHA-256 for $url" 1>&2; exit 1; }
  printf 'bootstrapJdkUrl.%s=%s\nbootstrapJdkSha256Sum.%s=%s\n' "$plat" "$url" "$plat" "$sha" >> "$jdkProperties"
  if [ "$plat" = "$from" ]; then
    echo "  $plat  $jdkVersion" 1>&2
  else
    echo "  $plat  $jdkVersion (the $from build)" 1>&2
  fi
done

./gradlew --quiet shadowJar -PjkiteVersion="$version"
jarSha=$(sha256_of build/libs/jkite.jar)

mkdir -p dist
for from in $copied; do
  cp -f "$from" "dist/$(basename "$from")"
done

{
  cat <<EOF
# What this project runs, and what it needs to run it. A project commits this
# file, not the binaries: the launcher scripts download each one once per
# machine, into ~/.jkite, and check it against the SHA-256 here.
#
# Written by misc/update-dist.sh; to move to another version run install.sh
# again rather than editing by hand. JKITE_DIST_URL overrides the jar's URL
# for one run, for a machine that cannot reach GitHub releases.

distributionVersion=$version
distributionUrl=$releaseBaseUrl/$repo/releases/download/v$version/jkite.jar
distributionSha256Sum=$jarSha

# The JDK a launcher installs when the machine has no usable Java, one entry
# per platform. It only has to run jkite.jar; the JDK a script asks for
# with //JAVA is installed by the jar itself.
bootstrapJdkVersion=$jdkVersion
EOF
  sort "$jdkProperties"
} > dist/jkite.properties

echo "dist/ refreshed for $version (bootstrap JDK $jdkVersion)" 1>&2
echo "Now: gh release create v$version dist/* build/libs/jkite.jar && git add dist && git commit" 1>&2
