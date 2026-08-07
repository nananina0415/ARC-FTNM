#!/usr/bin/env bash
set -euo pipefail

SUBTREE_PREFIX="apps/doom/PureDOOM"
UPSTREAM_URL="https://github.com/nananina0415/PureDOOM-NoFloat.git"
UPSTREAM_BRANCH="${1:-main}"
SYNC_BRANCH="original-puredoom"

CURRENT_BRANCH=$(git branch --show-current)

if [ -n "$(git status --porcelain)" ]; then
    echo "오류: 커밋되지 않은 변경이 있습니다. 먼저 커밋하거나 stash하세요."
    exit 1
fi

echo "==> [$SYNC_BRANCH] upstream 동기화 중... (브랜치: $UPSTREAM_BRANCH)"
git checkout "$SYNC_BRANCH"
git subtree pull --prefix="$SUBTREE_PREFIX" "$UPSTREAM_URL" "$UPSTREAM_BRANCH" --squash

echo "==> [$CURRENT_BRANCH] $SYNC_BRANCH 위로 리베이스 중..."
git checkout "$CURRENT_BRANCH"
git rebase "$SYNC_BRANCH"

echo ""
echo "완료. $UPSTREAM_BRANCH 의 변경이 $CURRENT_BRANCH 에 반영됐습니다."
echo "충돌이 있었다면 해결 후 'git rebase --continue' 를 실행하세요."
