#!/usr/bin/env bash
# Run the Phase A gates. Exit 0 only when every requested gate passes.
# Exit 2 means a gate passed locally but an external credential/account step is still pending.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="${1:-all}"

run_gate() {
  local name="$1" script="$ROOT/scripts/gates/verify_$1.sh"
  printf '\n\033[1m========== %s ==========\033[0m\n' "$name"
  bash "$script"
}

case "$TARGET" in
  a1|a2|a3) run_gate "$TARGET"; exit $? ;;
  all)
    RESULTS=()
    for gate in a1 a2 a3; do
      run_gate "$gate"
      RESULTS+=("$gate:$?")
    done
    printf '\n========== Phase A summary ==========\n'
    FINAL=0
    for result in "${RESULTS[@]}"; do
      gate="${result%%:*}"
      code="${result##*:}"
      case "$code" in
        0) printf '%-4s PASS\n' "$gate" ;;
        2) printf '%-4s PENDING (external account step)\n' "$gate"; [ "$FINAL" -eq 0 ] && FINAL=2 ;;
        *) printf '%-4s FAIL\n' "$gate"; FINAL=1 ;;
      esac
    done
    exit "$FINAL"
    ;;
  *)
    echo "usage: scripts/verify.sh [a1|a2|a3|all]" >&2
    exit 64
    ;;
esac
