#!/usr/bin/env bash
# A3 gate: the production artifact boots, serves health/metrics, and runs the demo.
# Set LEASH_DEMO_URL to verify the deployed Render service instead of the local container.
# Set LEASH_ADMIN_PASSWORD to include an authenticated SCRIPTED demo run in the gate.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR="$ROOT/backend/leash-backend/target/leash-backend-1.0.0-SNAPSHOT.jar"
LOCAL_PORT="${LEASH_LOCAL_PORT:-18080}"
BASE="${LEASH_DEMO_URL:-http://localhost:$LOCAL_PORT}"
REMOTE=0
[ -n "${LEASH_DEMO_URL:-}" ] && REMOTE=1
ADMIN_USER="${LEASH_ADMIN_USERNAME:-admin}"

APP_PID=""
LOG="$(mktemp)"
COOKIE_JAR="$(mktemp)"
cleanup() {
  if [ -n "$APP_PID" ]; then
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
  rm -f "$LOG" "$COOKIE_JAR"
}
trap cleanup EXIT

step() { printf '\n==> %s\n' "$1"; }
ok() { printf 'PASS  %s\n' "$1"; }
pending() { printf 'PENDING  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1" >&2; [ -s "$LOG" ] && tail -n 40 "$LOG" >&2; exit 1; }

wait_for_health() {
  for _ in $(seq 1 60); do
    if curl -fsS --max-time 5 "$BASE/actuator/health" >/dev/null 2>&1; then
      return 0
    fi
    if [ -n "$APP_PID" ] && ! kill -0 "$APP_PID" 2>/dev/null; then
      fail "application exited before becoming healthy"
    fi
    sleep 2
  done
  fail "health did not become UP within 120s"
}

step "Target"
echo "URL: $BASE ($([ "$REMOTE" -eq 1 ] && echo remote || echo local))"
if [ "$REMOTE" -eq 0 ]; then
  docker info >/dev/null 2>&1 || fail "Docker must be running for the local gate"
fi

if [ "$REMOTE" -eq 0 ]; then
  step "Build the production jar"
  (cd "$ROOT/backend" && ./mvnw -B -DskipTests package)
  [ -f "$JAR" ] || fail "missing $JAR"

  step "Local PostgreSQL"
  docker compose -f "$ROOT/docker-compose.yml" up -d
  for _ in $(seq 1 30); do
    STATUS="$(docker inspect -f '{{.State.Health.Status}}' leash-postgres 2>/dev/null || echo missing)"
    [ "$STATUS" = "healthy" ] && break
    sleep 2
  done
  [ "$STATUS" = "healthy" ] || fail "leash-postgres is $STATUS"
  ok "PostgreSQL is healthy"

  step "Boot the jar"
  pushd "$ROOT/backend" >/dev/null
  PORT="$LOCAL_PORT" DB_HOST=127.0.0.1 DB_PORT=5432 DB_NAME=leash DB_USER=leash \
    DB_PASSWORD=leash-local-only SPRING_PROFILES_ACTIVE=local,demo \
    java -jar "$JAR" >"$LOG" 2>&1 &
  APP_PID=$!
  popd >/dev/null
fi

step "Health"
wait_for_health
curl -fsS "$BASE/actuator/health" | grep -q '"status":"UP"' || fail "health is not UP"
ok "health is UP"

step "Readiness probe"
curl -fsS "$BASE/actuator/health/readiness" | grep -q '"status":"UP"' || fail "readiness is not UP"
ok "readiness is UP"

step "Prometheus metrics"
# Metrics are admin-only. Without credentials, prove the endpoint is protected; with
# LEASH_ADMIN_PASSWORD, also prove the JVM and HTTP series are present.
if [ -z "${LEASH_ADMIN_PASSWORD:-}" ]; then
  METRICS_STATUS="$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/actuator/prometheus")"
  case "$METRICS_STATUS" in
    401|403) ok "Prometheus is protected (HTTP $METRICS_STATUS); set LEASH_ADMIN_PASSWORD to verify series" ;;
    *) fail "expected /actuator/prometheus to require admin auth, got HTTP $METRICS_STATUS" ;;
  esac
else
  METRICS="$(curl -fsS -u "$ADMIN_USER:$LEASH_ADMIN_PASSWORD" "$BASE/actuator/prometheus")" \
    || fail "authenticated Prometheus scrape failed"
  echo "$METRICS" | grep -q '^jvm_memory_used_bytes' || fail "JVM metrics missing"
  echo "$METRICS" | grep -q '^http_server_requests_seconds_count' || fail "HTTP metrics missing"
  COUNT="$(echo "$METRICS" | grep -c '^[a-zA-Z_][a-zA-Z0-9_]*{')"
  [ "$COUNT" -ge 10 ] || fail "expected at least 10 metric series, found $COUNT"
  ok "Prometheus exposes $COUNT series (JVM + HTTP, admin-authenticated)"
fi

step "Scripted demo (3 scenes)"
if [ -z "${LEASH_ADMIN_PASSWORD:-}" ]; then
  pending "set LEASH_ADMIN_PASSWORD to include the authenticated demo run"
else
  curl -fsS -c "$COOKIE_JAR" -u "$ADMIN_USER:$LEASH_ADMIN_PASSWORD" \
    "$BASE/api/v1/admin/demo/status" >/dev/null || fail "admin login or demo status failed"
  XSRF="$(awk '$6 == "XSRF-TOKEN" {print $7}' "$COOKIE_JAR")"
  [ -n "$XSRF" ] || fail "XSRF-TOKEN cookie missing"
  RESPONSE="$(curl -fsS -b "$COOKIE_JAR" -u "$ADMIN_USER:$LEASH_ADMIN_PASSWORD" \
    -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
    -X POST "$BASE/api/v1/admin/demo/run?mode=SCRIPTED")"
  echo "$RESPONSE" | node -e '
    let raw = "";
    process.stdin.on("data", (chunk) => (raw += chunk));
    process.stdin.on("end", () => {
      const body = JSON.parse(raw);
      const scenes = body.scenes || [];
      if (scenes.length !== 3) {
        console.error(`expected 3 scenes, got ${scenes.length}`);
        process.exit(1);
      }
      console.log(scenes.map((s) => s.sceneId || s.invoiceId || "scene").join(", "));
    });
  ' || fail "demo did not return three scenes"
  ok "SCRIPTED demo returned three scenes"
fi

step "A3 gate"
if [ "$REMOTE" -eq 0 ]; then
  pending "local artifact is verified; set LEASH_DEMO_URL to verify the public Render deployment"
  exit 2
fi
ok "leash-backend:1.0.0-SNAPSHOT is live and observable"
