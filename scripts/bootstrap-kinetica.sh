#!/usr/bin/env bash
set -euo pipefail
terminal_root="$(cd "$(dirname "$0")/.." && pwd)"
framework_root="${1:-${KINETICA_FRAMEWORK_DIR:-$terminal_root/../kinetica}}"
if [[ ! -f "$framework_root/kinetica-application/module.yaml" ]]; then
  printf 'Expected a Kinetica checkout with the application shell at %s\n' "$framework_root" >&2
  exit 1
fi
cd "$framework_root"
./kotlin publish mavenLocal -m kinetica-compiler
if [[ "$(uname -s)" == Darwin ]]; then
  ./kotlin publish mavenLocal -m kinetica-appkit -m kinetica-browser --transitive
else
  ./kotlin publish mavenLocal -m kinetica-browser --transitive
fi
