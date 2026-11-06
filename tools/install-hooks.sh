#!/bin/sh
set -eu

cd "$(git rev-parse --show-toplevel)"
source_hook="tools/hooks/pre-commit"
target_hook="$(git rev-parse --git-path hooks/pre-commit)"

if [ -e "$target_hook" ] || [ -L "$target_hook" ]; then
    if ! cmp -s "$source_hook" "$target_hook"; then
        echo "Refusing to replace existing hook: $target_hook. Integrate tools/hooks/pre-commit manually." >&2
        exit 1
    fi
fi

mkdir -p "$(dirname "$target_hook")"
install -m 755 "$source_hook" "$target_hook"
echo "Installed Betterleaks pre-commit hook: $target_hook"
