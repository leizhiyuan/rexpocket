#!/usr/bin/env bash
# Rebuilds the two local branches the work is reviewed as, from the current tip:
#
#   split/ui       PR A, "UI and Rex parity": the merge base plus a few commits by topic, with no
#                  Netbird code, strings, options, docs or workflows.
#   split/netbird  PR B, "Netbird": split/ui plus the opt-in Netbird backend. Its tree is the tip's
#                  tree, less the split tooling itself (tools/split/, docs/design/pr-split.md).
#
# Nothing is pushed and the working tree is not touched: the trees are built in a temporary
# worktree and the commits with git plumbing, with the tip's author and dates, so the same tip
# (and the same cached library, below) gives the same commits every time. See docs/design/pr-split.md.
#
# Usage: tools/split/split-prs.sh [options]
#   --tip REF          what to split (default HEAD)
#   --base REF         the upstream branch (default origin/master); the split starts at the
#                      merge base of it and the tip
#   --aar FILE         the Tailscale-only tailnet.aar to put on split/ui, instead of building it
#   --refresh-patch    write tools/split/netbird.patch from the existing split/ui and the tip,
#                      after split/ui was fixed by hand (see the doc), and stop
#   --keep             keep the temporary worktree, for a look at a failure
#
# Building the aar needs what tools/build-tailnet.sh needs (Go, ANDROID_HOME, ANDROID_NDK_HOME,
# JAVA_HOME). The result is cached under the git directory by the tree it was built from, so a
# rerun with the same tailnet/ sources reuses it.
set -euo pipefail
# Pathspecs such as :(glob)**/Foo* are for git, not the shell.
set -f

TIP_REF=HEAD
BASE_REF=origin/master
AAR=
REFRESH=0
KEEP=0
while [ $# -gt 0 ]; do
    case "$1" in
        --tip) TIP_REF=$2; shift 2 ;;
        --base) BASE_REF=$2; shift 2 ;;
        --aar) AAR=$(realpath "$2"); shift 2 ;;
        --refresh-patch) REFRESH=1; shift ;;
        --keep) KEEP=1; shift ;;
        -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
SPLIT=tools/split
TIP=$(git rev-parse --verify "$TIP_REF^{commit}")
BASE=$(git merge-base "$BASE_REF" "$TIP")
TIP_TREE=$(git rev-parse "$TIP^{tree}")

say() { printf '%s\n' "$*" >&2; }
die() { say "split-prs: $*"; exit 1; }

# The control files are read from the tip, not the working tree, so the split is the tip's own.
control() { git show "$TIP:$SPLIT/$1"; }
paths_of() { control "$1" | sed -e 's/#.*//' -e 's/[[:space:]]*$//' -e '/^$/d'; }

mapfile -t EXCLUDED < <(paths_of excluded-paths.txt)
mapfile -t NETBIRD_ONLY < <(paths_of netbird-paths.txt)
# Files the split makes for split/ui itself, rather than taking them from a patch.
GENERATED=(tailnet/go.mod tailnet/go.sum app/libs/tailnet.aar)

exclude_specs() { local p; for p in "$@"; do printf ':(exclude)%s\n' "$p"; done; }

if [ "$REFRESH" = 1 ]; then
    UI=$(git rev-parse --verify split/ui^{commit}) || die "no split/ui to refresh the patch from"
    mapfile -t SPECS < <(exclude_specs "${EXCLUDED[@]}" "${NETBIRD_ONLY[@]}" "${GENERATED[@]}")
    git diff --full-index --no-renames "$UI" "$TIP" -- . "${SPECS[@]}" > "$SPLIT/netbird.patch"
    say "wrote $SPLIT/netbird.patch ($(grep -c '^diff --git' "$SPLIT/netbird.patch") files); commit it with the tip"
    exit 0
fi

TMP=$(mktemp -d "${TMPDIR:-/tmp}/split-prs.XXXXXX")
WT="$TMP/tree"
cleanup() {
    if [ "$KEEP" = 1 ]; then say "kept $WT"; return; fi
    git worktree remove --force "$WT" 2>/dev/null || true
    rm -rf "$TMP"
}
trap cleanup EXIT
git worktree add -q --detach "$WT" "$TIP"
g() { git -C "$WT" "$@"; }

# --- PR B's tree: the tip, less the split tooling.
for p in "${EXCLUDED[@]}"; do g rm -r -q --ignore-unmatch -- "$p"; done
TREE_B=$(g write-tree)

# --- PR A's tree: PR B's, less what only Netbird has, and with the Netbird hunks of shared files
# taken out again.
for p in "${NETBIRD_ONLY[@]}"; do
    g rm -r -q --ignore-unmatch -- "$p"
    # A path the merge base already had goes back to how it was there.
    if git cat-file -e "$BASE:$p" 2>/dev/null; then g checkout "$BASE" -- "$p"; fi
done
control netbird.patch > "$TMP/netbird.patch"
if ! g apply --index -R --3way "$TMP/netbird.patch"; then
    say "netbird.patch no longer comes out of the tip cleanly: fix the conflicts in $WT (kept),"
    say "then see 'When the patch stops applying' in docs/design/pr-split.md."
    KEEP=1
    exit 1
fi

# tailnet/go.mod without the Netbird module, keeping every remaining module at the tip's version so
# the Tailscale node is built from the same code as the tip's default library.
(
    cd "$WT/tailnet"
    export GOFLAGS=-mod=mod GOTOOLCHAIN="${GOTOOLCHAIN:-auto}"
    # The module of every package the build and the tests compile, with its version.
    modules() { go list -deps -test -f '{{with .Module}}{{if not .Main}}{{.Path}} {{.Version}}{{end}}{{end}}' ./... | sort -u; }
    git -C "$WT" show "$TIP:tailnet/go.mod" > go.mod
    git -C "$WT" show "$TIP:tailnet/go.sum" > go.sum
    mkdir -p "$TMP/tip-tailnet"
    git -C "$WT" archive "$TIP" tailnet | tar -x -C "$TMP/tip-tailnet"
    (cd "$TMP/tip-tailnet/tailnet" && modules) > "$TMP/tip-modules.txt"
    go mod edit -droprequire=github.com/netbirdio/netbird
    # The replace directives came with Netbird (copied from its go.mod); the ones the merge base had stay.
    replaced() { go mod edit -json "$1" | python3 -I -c 'import json, sys; [print(r["Old"]["Path"]) for r in json.load(sys.stdin).get("Replace") or []]' | sort; }
    git -C "$WT" show "$BASE:tailnet/go.mod" > "$TMP/base.go.mod"
    for r in $(comm -23 <(replaced go.mod) <(replaced "$TMP/base.go.mod")); do go mod edit -dropreplace="$r"; done
    for _ in 1 2 3 4; do
        go mod tidy
        changed=0
        while read -r path version; do
            want=$(awk -v p="$path" '$1 == p { print $2 }' "$TMP/tip-modules.txt")
            if [ -n "$want" ] && [ "$want" != "$version" ]; then
                go mod edit -require="$path@$want"
                changed=1
            fi
        done < <(modules)
        [ "$changed" = 0 ] && break
    done
    go build ./...
)
g add tailnet/go.mod tailnet/go.sum

# The Tailscale-only library: built from split/ui's tailnet/ (no Netbird package, so no Netbird
# classes either), or handed in.
TREE_NOW=$(g write-tree)
TAILNET_KEY=$(git rev-parse "$TREE_NOW:tailnet")
BUILD_KEY=$(git rev-parse "$TREE_NOW:tools/build-tailnet.sh")
CACHE="$(git rev-parse --git-common-dir)/split-cache"
CACHED="$CACHE/tailnet-$TAILNET_KEY-$BUILD_KEY.aar"
if [ -z "$AAR" ]; then
    if [ ! -f "$CACHED" ]; then
        say "building the Tailscale-only tailnet.aar (cached as $CACHED)"
        sh "$WT/tools/build-tailnet.sh" >&2
        mkdir -p "$CACHE"
        cp "$WT/app/libs/tailnet.aar" "$CACHED"
    fi
    AAR=$CACHED
fi
cp "$AAR" "$WT/app/libs/tailnet.aar"
if unzip -l "$WT/app/libs/tailnet.aar" | grep -qi netbird; then die "$AAR has Netbird classes in it"; fi
g add app/libs/tailnet.aar
TREE_A=$(g write-tree)

# --- No Netbird on split/ui, apart from what grep-allow.txt lets through.
mapfile -t ALLOW < <(paths_of grep-allow.txt)
mapfile -t ALLOW_SPECS < <(exclude_specs "${ALLOW[@]}")
if hits=$(git grep -I -i -l netbird "$TREE_A" -- . "${ALLOW_SPECS[@]}"); then
    say "split/ui still mentions Netbird in:"
    say "$hits"
    say "Take it out in $WT (kept), or list a new Netbird-only path in netbird-paths.txt; see"
    say "'When the patch stops applying' in docs/design/pr-split.md."
    KEEP=1
    exit 1
fi

# --- The commits: one per topic, each with the files of its pathspecs not taken by an earlier one.
NAME=$(git log -1 --format=%an "$TIP"); EMAIL=$(git log -1 --format=%ae "$TIP")
DATE=$(git log -1 --format=%cI "$TIP")
export GIT_AUTHOR_NAME="$NAME" GIT_AUTHOR_EMAIL="$EMAIL" GIT_AUTHOR_DATE="$DATE"
export GIT_COMMITTER_NAME="$NAME" GIT_COMMITTER_EMAIL="$EMAIL" GIT_COMMITTER_DATE="$DATE"

# commits FROM_COMMIT TO_TREE TOPICS_FILE PR_NAME: prints the last commit.
commits() {
    local from=$1 to=$2 topics=$3 pr=$4
    local index="$TMP/index" done="$TMP/done" prev=$from n=0 subject specs files
    : > "$done"
    rm -f "$index"
    GIT_INDEX_FILE=$index git read-tree "$from"
    while IFS=$'\t' read -r subject specs; do
        [ -n "$subject" ] || continue
        case "$subject" in \#*) continue ;; esac
        # shellcheck disable=SC2086
        files=$(git diff --name-only --no-renames "$from" "$to" -- $specs | grep -vxF -f "$done" || true)
        [ -n "$files" ] || continue
        printf '%s\n' "$files" >> "$done"
        while IFS= read -r f; do
            if git cat-file -e "$to:$f" 2>/dev/null; then
                git ls-tree "$to" -- "$f" | GIT_INDEX_FILE=$index git update-index --index-info
            else
                GIT_INDEX_FILE=$index git update-index --force-remove -- "$f"
            fi
        done <<< "$files"
        n=$((n + 1))
        prev=$(GIT_INDEX_FILE=$index git write-tree | xargs -I{} git commit-tree {} -p "$prev" \
            -m "$subject" -m "$pr, part $n. Made by tools/split/split-prs.sh from $(git rev-parse --short "$TIP").")
        say "  $(git rev-parse --short "$prev") $subject ($(printf '%s\n' "$files" | wc -l) files)"
    done < <(control "$topics")
    [ "$(git rev-parse "$prev^{tree}")" = "$to" ] || die "the topics of $topics do not cover every file; the last pattern should be '.'"
    printf '%s\n' "$prev"
}

say "split/ui on $(git rev-parse --short "$BASE"):"
UI=$(commits "$BASE" "$TREE_A" ui-topics.txt "PR A (UI and Rex parity)")
say "split/netbird on split/ui:"
NB=$(commits "$UI" "$TREE_B" netbird-topics.txt "PR B (Netbird)")
git branch -f split/ui "$UI"
git branch -f split/netbird "$NB"

# --- The invariant: split/netbird is the tip, less the split tooling and nothing else.
DIFF=$(git diff --name-only "$NB" "$TIP")
mapfile -t EXPECTED < <(git diff --name-only "$TREE_B" "$TIP_TREE")
[ "$DIFF" = "$(printf '%s\n' "${EXPECTED[@]}")" ] || die "split/netbird differs from the tip in more than the split tooling"
say "split/netbird = tip except: $(printf '%s ' $DIFF)"
say "split/ui      $(git rev-parse --short "$UI")  ($(git rev-list --count "$BASE..$UI") commits)"
say "split/netbird $(git rev-parse --short "$NB")  ($(git rev-list --count "$UI..$NB") commits)"
