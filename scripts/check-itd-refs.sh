#!/usr/bin/env bash
# Fails when the integration-test-data refs pinned across this repo's workflows
# differ (qfg-6vno.7). The unit pin (test.yaml), chaos.yaml and
# failover-chaos.yaml must name ONE ITD tag, or the chaos gates silently run
# older scenarios than the unit suite.
#
# A ref belongs to ITD when it sits in the same `with:` block as
# `repository: quonfig/integration-test-data`. Usage: scripts/check-itd-refs.sh [workflows-dir]
set -euo pipefail

dir="${1:-$(dirname "$0")/../.github/workflows}"
refs=$(
  for f in "$dir"/*.yml "$dir"/*.yaml; do
    [ -f "$f" ] || continue
    awk -v file="$(basename "$f")" '
      /^[[:space:]]*-[[:space:]]/ { itd = 0; ref = "" }
      /repository:[[:space:]]*quonfig\/integration-test-data/ { itd = 1 }
      /^[[:space:]]*ref:[[:space:]]*/ { r = $0; sub(/^[[:space:]]*ref:[[:space:]]*/, "", r); sub(/[[:space:]]*#.*$/, "", r); ref = r }
      itd && ref != "" { print file ": " ref; itd = 0; ref = "" }
    ' "$f"
  done
)

if [ -z "$refs" ]; then
  echo "check-itd-refs: no integration-test-data checkouts found under $dir" >&2
  exit 1
fi
echo "$refs"
distinct=$(echo "$refs" | sed 's/^[^:]*: //' | sort -u)
if [ "$(echo "$distinct" | wc -l | tr -d ' ')" -ne 1 ]; then
  echo "check-itd-refs: integration-test-data refs differ across workflows:" >&2
  echo "$distinct" | sed 's/^/  /' >&2
  exit 1
fi
echo "check-itd-refs: OK, every workflow pins integration-test-data at $distinct"
