#!/usr/bin/env bash
# Brings the files listed in misc/upstream-mirror.txt in from jbangdev/jbang.
#
#   misc/sync-upstream.sh [<ref>]      # default: upstream/main
#   misc/sync-upstream.sh --check      # is the mirror still a mirror?
#
# The mirrored files are never edited locally, so a sync is always a clean
# overwrite. Afterwards build and test, then commit the result together with
# the updated misc/upstream-ref.txt.
#
# --check answers the question the overwrite makes it too late to ask: an edit
# to a mirrored file changes nothing anyone can see, and then disappears at the
# next sync. It reads only; CI runs it.
set -euo pipefail
cd "$(dirname "$0")/.."

UPSTREAM_URL=${JKITE_UPSTREAM_URL:-https://github.com/jbangdev/jbang}
REF=${1:-upstream/main}
MIRROR=misc/upstream-mirror.txt
SHIMS=misc/upstream-shims.txt
REFFILE=misc/upstream-ref.txt

# Every file that sits at an upstream path, with the list that says what may be
# done to it. A file in neither is one nobody knows the rules for.
check_lists() {
  local failed=0 listed f count
  listed=$(cat "$MIRROR" "$SHIMS" | grep -vE '^[[:space:]]*(#|$)')
  while IFS= read -r f; do
    count=$(printf '%s\n' "$listed" | grep -cxF "$f" || true)
    if [ "$count" -eq 0 ]; then
      echo "In neither $MIRROR nor $SHIMS, so nothing says whether it may be edited: $f" 1>&2
      failed=1
    elif [ "$count" -gt 1 ]; then
      echo "Listed $count times: $f" 1>&2
      failed=1
    fi
  done < <(find src -path '*/dev/jbang/*' -name '*.java' | sort)
  while IFS= read -r f; do
    if [ ! -f "$f" ]; then
      echo "Listed but not in the tree: $f" 1>&2
      failed=1
    fi
  done <<< "$listed"
  return $failed
}

# A mirrored file is a copy, so it has to be equal to what it was copied from -
# at the revision misc/upstream-ref.txt records, not at upstream's head, which
# moves on its own.
check_mirror() {
  local failed=0 ref f
  ref=$(cat "$REFFILE")
  echo "Comparing the mirrored files with $UPSTREAM_URL at $ref"
  # the one commit, rather than the remote's whole history: a second or two
  if ! git fetch --depth 1 "$UPSTREAM_URL" "$ref" > /dev/null 2>&1; then
    echo "Could not fetch $ref from $UPSTREAM_URL" 1>&2
    return 1
  fi
  while IFS= read -r f; do
    if ! git show "FETCH_HEAD:$f" 2> /dev/null | cmp -s - "$f"; then
      echo "Not what upstream has at $ref: $f" 1>&2
      failed=1
    fi
  done < <(grep -vE '^[[:space:]]*(#|$)' "$MIRROR")
  return $failed
}

if [ "${1:-}" = "--check" ]; then
  lists=0
  mirror=0
  check_lists || lists=1
  check_mirror || mirror=1
  if [ "$lists" -ne 0 ]; then
    echo 1>&2
    echo "Add the file to $MIRROR if it is copied from upstream, or to $SHIMS" 1>&2
    echo "if it keeps upstream's API over an implementation of our own. Which" 1>&2
    echo "list it is in is what says whether it may be edited here." 1>&2
  fi
  if [ "$mirror" -ne 0 ]; then
    echo 1>&2
    echo "A mirrored file has been edited here. The next sync overwrites it, so" 1>&2
    echo "the change would be lost without a word: take it out, and send it to" 1>&2
    echo "JBang instead - there it survives, and everyone gets it." 1>&2
  fi
  if [ "$lists" -ne 0 ] || [ "$mirror" -ne 0 ]; then
    exit 1
  fi
  echo "The mirror matches upstream, and every file under dev/jbang is accounted for."
  exit 0
fi

if ! git remote get-url upstream > /dev/null 2>&1; then
  echo "Adding the 'upstream' remote: $UPSTREAM_URL"
  git remote add upstream "$UPSTREAM_URL"
fi
git fetch upstream

files=$(grep -vE '^\s*(#|$)' "$MIRROR")
echo "Syncing $(echo "$files" | wc -l | tr -d ' ') files from $REF"
# shellcheck disable=SC2086
git checkout "$REF" -- $files

new=$(git rev-parse "$REF")
old=$(cat "$REFFILE" 2>/dev/null || echo "")
echo "$new" > "$REFFILE"

if [ -n "$old" ] && [ "$old" != "$new" ]; then
  echo
  echo "Changes to the mirrored files between $old and $new:"
  git --no-pager log --oneline "$old..$new" -- $files || true
  echo
  echo "Changes upstream made to the files jkite only shims:"
  shims=$(grep -vE '^\s*(#|$)' "$SHIMS")
  # shellcheck disable=SC2086
  git --no-pager log --oneline "$old..$new" -- $shims || true
  echo "(review those by hand, they are not overwritten)"
fi

echo
echo "Now run: ./gradlew build"
