#!/usr/bin/env bash
#
# Installs jkite into a project: the launcher scripts, this installer and
# jkite.properties go into jkite/, which is committed, so the project
# can be built and run without jkite (or a JDK) being installed on the
# machine.
#
#   curl -fsSL https://github.com/instreest/jkite/releases/latest/download/install.sh | bash
#
# Everything comes from a GitHub release, over https. jkite.jar and a JDK
# are not installed here: jkite.properties pins the version, URL and
# SHA-256 of each, and the launcher downloads and verifies them once per
# machine, into ~/.jkite. So a project's history carries about 98 kB of
# scripts rather than binaries. A project that would rather vendor the jar can
# drop a jkite.jar into jkite/ next to the launcher, and then only a
# JDK is ever fetched.
#
# Running it again updates an existing installation: every file, the properties
# included, is replaced by the one from the chosen release.
#
# Usage: install.sh [<target directory>]   (default: ./jkite, or the directory
#                                           this script was installed in)
#
# Environment:
#   JKITE_REPO          GitHub repository to install from (default instreest/jkite)
#   JKITE_REF           release tag to install (default: the latest release)
#   JKITE_DIST_BASEURL  install from here instead of from a GitHub release
set -eu

# Everything below is one function, called on the last line, because the
# documented way to run this is "curl ... | bash": bash reads a pipe as it
# goes and runs each command as soon as it has read it. A connection that
# drops halfway therefore runs half an installation - far enough to have
# moved some of the files into place, not far enough to have moved the rest -
# and a truncated script that ends between two commands exits 0, so the last
# thing said is the "Installing..." line and the project is left with a jkite/
# that is missing files nobody was told about. Defining a function and calling
# it at the end means a truncated copy defines something and never calls it.
jkite_install() {
  repo=${JKITE_REPO:-instreest/jkite}
  ref=${JKITE_REF:-}
  if [ -n "${JKITE_DIST_BASEURL:-}" ]; then
    base=$JKITE_DIST_BASEURL
  elif [ -n "$ref" ]; then
    base="https://github.com/$repo/releases/download/$ref"
  else
    # GitHub redirects this to the newest release, so no release has to be looked
    # up and no API has to be called
    base="https://github.com/$repo/releases/latest/download"
  fi

  # A plaintext install would let anyone on the path replace the scripts a
  # project is about to commit. A loopback address is allowed so that the tests
  # can serve a release locally.
  case "$base" in
    https://*) ;;
    http://127.0.0.1[:/]*|http://localhost[:/]*) ;;
    *) echo "Refusing to install over anything but https: $base" 1>&2; exit 1;;
  esac

  # what a project gets; dist/ in the repository holds the same set
  files="jkite jkite.cmd jkite-bootstrap-jdk jkite-bootstrap-jdk.cmd
         jkite-bootstrap-jar jkite-bootstrap-jar.cmd jkite.properties
         install.sh install.cmd README.md LICENSE"

  # Says what failed and exits 1, the way install.cmd already did. Without
  # this, "set -e" ends the run on curl's own exit code - 22 for an HTTP
  # error - and the only thing printed is "curl: (22) The requested URL
  # returned error: 404", which names neither the file nor the release it was
  # not in. The commonest way to get here is a tag that does not exist.
  fetch_failed() {
    echo "Could not download $1 from $base" 1>&2
    echo "Check that the release exists and that this machine can reach it." 1>&2
    exit 1
  }

  fetch() {  # $1 = file to fetch, $2 = file to write
    if command -v curl > /dev/null 2>&1; then
      curl -fsSL --proto '=https,http' --proto-redir '=https' "$base/$1" -o "$2" || fetch_failed "$1"
    elif command -v wget > /dev/null 2>&1; then
      # as for curl above: no redirect off https, except for the loopback the
      # tests serve on, which the check above has already allowed through
      case "$base" in
        https://*) wget -q --https-only "$base/$1" -O "$2" || fetch_failed "$1" ;;
        *) wget -q "$base/$1" -O "$2" || fetch_failed "$1" ;;
      esac
    else
      echo "Neither curl nor wget is available" 1>&2
      exit 1
    fi
  }

  if [ $# -gt 0 ]; then
    dir=$1
  elif [ -n "${BASH_SOURCE[0]:-}" ] && [ "$(basename "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)")" = jkite ]; then
    # updating an existing installation
    dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
  else
    dir=$PWD/jkite
  fi

  # Everything is fetched into a staging directory first, so a failed download
  # leaves an existing installation as it was
  staging=$(mktemp -d "${TMPDIR:-/tmp}/jkite.XXXXXX")
  trap 'rm -rf "$staging"; rm -f "$dir"/*.jkite-new.$$' EXIT

  echo "Installing jkite from $base into $dir" 1>&2
  for f in $files; do
    fetch "$f" "$staging/$f"
  done

  # The mode is set here rather than on the installed copy, so that each file
  # arrives complete and executable in one step rather than in two.
  chmod +x "$staging/jkite" "$staging/jkite-bootstrap-jdk" \
           "$staging/jkite-bootstrap-jar" "$staging/install.sh"

  mkdir -p "$dir"
  # Written beside the target and renamed into place, never copied onto it.
  # install.sh is one of these files, and on an update it is the script bash is
  # reading: cp truncates and rewrites that same inode, and bash then seeks back
  # to its saved offset in what is now different content and runs whatever lies
  # there. rename(2) gives the new bytes a new inode and leaves the running
  # script's descriptor on the old one, which stays readable until it exits.
  # It has to be a rename within $dir - moving from $staging is usually a move
  # across filesystems, which is a copy onto the target again.
  for f in $files; do
    cp -f "$staging/$f" "$dir/$f.jkite-new.$$"
    mv -f "$dir/$f.jkite-new.$$" "$dir/$f"
  done

  echo "Installed. Commit $(basename "$dir")/ and run '$(basename "$dir")/jkite <script.java>'." 1>&2
}

jkite_install "$@"
