#!/usr/bin/env bash
# A1 gate: leash-core is extracted, framework-free, and the app still passes every check.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CORE="$ROOT/backend/leash-core"
APP="$ROOT/backend/leash-backend"

step() { printf '\n==> %s\n' "$1"; }
ok() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1" >&2; exit 1; }

step "Toolchain"
command -v java >/dev/null || fail "java is not on PATH"
java -version 2>&1 | grep -q 'version "21' || fail "Java 21 is required"
docker info >/dev/null 2>&1 || fail "Docker must be running (Testcontainers integration tests)"
ok "Java 21 and Docker are available"

step "Reactor layout"
grep -q '<module>leash-core</module>' "$ROOT/backend/pom.xml" || fail "parent pom does not list leash-core"
grep -q '<module>leash-backend</module>' "$ROOT/backend/pom.xml" || fail "parent pom does not list leash-backend"
grep -q '<artifactId>leash-core</artifactId>' "$APP/pom.xml" || fail "leash-backend does not depend on leash-core"
ok "backend/pom.xml aggregates leash-core + leash-backend"

step "Framework purity (source)"
if grep -RE 'import (org\.springframework|jakarta\.)' "$CORE/src/main"; then
  fail "leash-core imports Spring or Jakarta APIs"
fi
ok "leash-core has zero Spring/Jakarta imports"

step "Allowed core packages"
bad_packages="$(grep -Rh '^package com\.leash' "$CORE/src/main" \
  | sed 's/^package //; s/;$//' \
  | grep -Ev '^com\.leash\.(policy|common\.money|payment\.domain|paypal)$' || true)"
[ -z "$bad_packages" ] || fail "unexpected core packages: $bad_packages"
ok "only policy, money, payment domain, and provider ports live in core"

step "Full reactor verify (unit + integration + Spotless + JaCoCo)"
(cd "$ROOT/backend" && ./mvnw -B verify)

step "Artifacts"
CORE_JAR="$CORE/target/leash-core-1.0.0-SNAPSHOT.jar"
APP_JAR="$APP/target/leash-backend-1.0.0-SNAPSHOT.jar"
[ -f "$CORE_JAR" ] || fail "missing $CORE_JAR"
[ -f "$APP_JAR" ] || fail "missing $APP_JAR"
[ -f "$CORE/target/leash-core-1.0.0-SNAPSHOT-sources.jar" ] || fail "sources jar missing"
ok "leash-core and leash-backend jars built"

step "Test evidence"
CORE_TESTS="$(find "$CORE/target/surefire-reports" -name 'TEST-*.xml' -exec grep -c '<testcase' {} + 2>/dev/null | awk -F: '{sum += $NF} END {print sum + 0}')"
APP_TESTS="$(find "$APP/target/surefire-reports" -name 'TEST-*.xml' -exec grep -c '<testcase' {} + 2>/dev/null | awk -F: '{sum += $NF} END {print sum + 0}')"
echo "core tests: $CORE_TESTS, backend tests: $APP_TESTS, total: $((CORE_TESTS + APP_TESTS))"
[ "$CORE_TESTS" -ge 100 ] || fail "expected at least 100 core tests"
[ "$APP_TESTS" -ge 100 ] || fail "expected at least 100 backend tests"

step "A1 gate"
ok "leash-core:1.0.0-SNAPSHOT extracted, all checks green"
