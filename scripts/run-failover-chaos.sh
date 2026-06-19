#!/usr/bin/env bash
#
# Run the failover + canonical-ordering chaos rigs against sdk-java (bead qfg-7h5d.1.10).
#
# Unlike run-chaos.sh (single upstream), these two rigs spawn their own
# api-delivery fixture upstream(s) from inside the JUnit test
# (FailoverChaosTest):
#   - failoverScenarios() drives scenarios-failover/ against ONE upstream behind
#     the primary ('http') + 'secondary' proxies; faults hit the primary leg.
#   - orderingScenarios() drives scenarios-ordering/ against TWO upstreams pinned
#     to divergent Meta.generations (one per scenario).
#
# So this wrapper only needs to boot toxiproxy; the test repoints the seeded
# 'http'/'secondary'/'sse' proxies at the upstreams it spawns.
#
# Env knobs:
#   CHAOS_SKIP   comma-separated scenario-name substrings to EXCLUDE, read by
#                FailoverChaosTest. Default is EMPTY now that the parallel-failover
#                hedge (qfg-7h5d.1.14) lands o01/o03/o05 — the full f01-f05 +
#                o01-o05 set runs. Set CHAOS_SKIP=o01,... to exclude a scenario.
#
# Examples:
#   ./scripts/run-failover-chaos.sh
#   CHAOS_SKIP=o01 ./scripts/run-failover-chaos.sh   # exclude o01

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_JAVA_DIR="$(cd "$HERE/.." && pwd)"
REPO_ROOT="$(cd "$SDK_JAVA_DIR/.." && pwd)"
HARNESS_DIR="$REPO_ROOT/integration-test-data/chaos"

if [[ ! -d "$HARNESS_DIR" ]]; then
  echo "chaos harness not found at $HARNESS_DIR — is integration-test-data checked out as a sibling?" >&2
  exit 1
fi

# Identify ourselves to the shared chaos lock (qfg-47c2.32). Owner PID is THIS
# wrapper's pid so the lock survives the whole run, not just the short-lived
# start-chaos.sh subprocess.
export QUONFIG_CHAOS_SESSION="${QUONFIG_CHAOS_SESSION:-sdk-java-failover-$$-$(date +%s)}"
export QUONFIG_CHAOS_OWNER_PID=$$

# Default to running EVERYTHING: the parallel-failover hedge (qfg-7h5d.1.14) makes
# o01 (fast primary wins, secondary never contacted), o03 (heal forward), and o05
# (slow older primary loses) all pass. The filter is a JUnit display-name substring
# matched in the test's @TestFactory dynamic-test names.
CHAOS_SKIP="${CHAOS_SKIP-}"

cleanup_done=0
cleanup() {
  if [[ "$cleanup_done" == "1" ]]; then return; fi
  cleanup_done=1
  echo "==> tearing down chaos harness"
  "$HARNESS_DIR/stop-chaos.sh" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

echo "==> booting toxiproxy via shared launcher (no upstream — the test spawns its own)"
"$HARNESS_DIR/start-chaos.sh"

echo "==> running failover + ordering scenarios (skip=${CHAOS_SKIP:-<none>})"
cd "$SDK_JAVA_DIR"
CHAOS_RUN=1 CHAOS_SKIP="$CHAOS_SKIP" \
  ./gradlew :core:test --tests "com.quonfig.sdk.chaos.FailoverChaosTest" --rerun --info
