#!/usr/bin/env bash
# Merges official Nuvio (<target>: a commit, branch or tag already fetched) into the current
# branch, committing it as <message>. A real merge records upstream history, so each sync only
# brings in what changed since the last one. The fork's own workflows always win.
#
# Upstream used to be copied in as patches, with the last synced commit recorded in
# .github/nuvio-upstream-base. The first merge records that commit as merged without changing
# any file (its content is already here) and retires the file.
#
#   merge-nuvio-upstream.sh <target> <message>
set -euo pipefail

TARGET="$(git rev-parse "$1^{commit}")"
MESSAGE="$2"
BASE_FILE=".github/nuvio-upstream-base"

if git merge-base --is-ancestor "$TARGET" HEAD; then
  echo "Upstream $TARGET is already merged."
  exit 0
fi

if [[ -f "$BASE_FILE" ]]; then
  UPSTREAM_BASE="$(tr -d '[:space:]' < "$BASE_FILE")"
  if [[ -z "$UPSTREAM_BASE" ]] || ! git cat-file -e "$UPSTREAM_BASE^{commit}" 2>/dev/null; then
    echo "Recorded upstream base '$UPSTREAM_BASE' is not available; refusing to guess." >&2
    exit 1
  fi
  if ! git merge-base --is-ancestor "$UPSTREAM_BASE" "$TARGET"; then
    echo "Recorded upstream base $UPSTREAM_BASE is not an ancestor of $TARGET." >&2
    echo "Upstream may have been rewritten; refusing an unsafe automatic sync." >&2
    exit 1
  fi
  if ! git merge-base --is-ancestor "$UPSTREAM_BASE" HEAD; then
    git merge --no-ff -s ours -m "Record Nuvio upstream ${UPSTREAM_BASE:0:12} as merged" "$UPSTREAM_BASE"
  fi
fi

set +e
git merge --no-ff --no-commit "$TARGET"
MERGE_STATUS=$?
set -e

# Fork-owned workflows always win (upstream's own are not brought in).
git restore --source=HEAD --staged --worktree .github/workflows

if (( MERGE_STATUS != 0 )); then
  mapfile -t CONFLICTS < <(git diff --name-only --diff-filter=U)
  if (( ${#CONFLICTS[@]} > 0 )); then
    printf 'Upstream merge conflicts, resolve them by hand: %s\n' "${CONFLICTS[*]}" >&2
    exit "$MERGE_STATUS"
  fi
  if [[ ! -f "$(git rev-parse --git-dir)/MERGE_HEAD" ]]; then
    echo "git merge failed without starting a merge." >&2
    exit "$MERGE_STATUS"
  fi
fi

if [[ -f "$BASE_FILE" ]]; then
  git rm -q "$BASE_FILE"
fi

git commit --no-edit -m "$MESSAGE"
echo "Merged upstream $TARGET."
