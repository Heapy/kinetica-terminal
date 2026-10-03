#!/usr/bin/env bash
# Build a graph containing only the independent engine, without resolving app UI plugins.
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
engine_project="$repo_root/build/engine-project"
mkdir -p "$engine_project"
ln -sfn "$repo_root/kinetica-terminal" "$engine_project/kinetica-terminal"
cp "$repo_root/common.module-template.yaml" "$repo_root/publish.module-template.yaml" "$engine_project/"
printf 'modules:\n  - ./kinetica-terminal\n' > "$engine_project/project.yaml"
command_name="${1:?Usage: bash scripts/engine.sh <test|build|publish|show> [arguments]}"
shift
exec "$repo_root/kotlin" "$command_name" --project-dir "$engine_project" "$@"
