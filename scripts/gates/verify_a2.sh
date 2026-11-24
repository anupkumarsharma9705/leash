#!/usr/bin/env bash
# A2 gate: leash-core is installable, deployable, and consumable.
# The remote half needs a GitHub token with packages:read (or a successful publish workflow run).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERSION="1.0.0-SNAPSHOT"
GROUP_PATH="com/leash/leash-core/$VERSION"
REPO_URL="https://maven.pkg.github.com/anupkumarsharma9705/leash"

step() { printf '\n==> %s\n' "$1"; }
ok() { printf 'PASS  %s\n' "$1"; }
pending() { printf 'PENDING  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1" >&2; exit 1; }

step "Publish workflow"
[ -f "$ROOT/.github/workflows/publish-core.yml" ] || fail "publish-core.yml is missing"
grep -q 'packages: write' "$ROOT/.github/workflows/publish-core.yml" || fail "workflow lacks packages: write"
grep -q "$REPO_URL" "$ROOT/backend/leash-core/pom.xml" || fail "core pom lacks the GitHub Packages repository"
ok "GitHub Packages distribution and tag workflow are configured"

step "Core verify (unit tests + purity + coverage)"
(cd "$ROOT/backend" && ./mvnw -B -pl leash-core -am verify)

step "Local install"
(cd "$ROOT/backend" && ./mvnw -B -pl leash-core -am install -DskipTests)
LOCAL_JAR="$HOME/.m2/repository/$GROUP_PATH/leash-core-$VERSION.jar"
[ -f "$LOCAL_JAR" ] || fail "artifact not found in the local repository: $LOCAL_JAR"
ok "installed com.leash:leash-core:$VERSION"

step "Fresh consumer resolves the published coordinates"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cat > "$TMP/pom.xml" <<EOF
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.leash.gate</groupId>
  <artifactId>consumer</artifactId>
  <version>1.0.0</version>
  <properties><maven.compiler.release>21</maven.compiler.release></properties>
  <dependencies>
    <dependency>
      <groupId>com.leash</groupId>
      <artifactId>leash-core</artifactId>
      <version>$VERSION</version>
    </dependency>
  </dependencies>
</project>
EOF
(cd "$ROOT/backend" && ./mvnw -B -q -f "$TMP/pom.xml" dependency:resolve)
ok "a clean project can resolve leash-core without the source tree"

step "Remote artifact (GitHub Packages)"
TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-$(gh auth token 2>/dev/null || true)}}"
if [ -z "$TOKEN" ]; then
  pending "no GITHUB_TOKEN/GH_TOKEN; cannot check maven.pkg.github.com"
  exit 2
fi
STATUS="$(curl -s -o /dev/null -w '%{http_code}' \
  -u "${GITHUB_ACTOR:-anupkumarsharma9705}:$TOKEN" \
  "$REPO_URL/$GROUP_PATH/maven-metadata.xml")"
case "$STATUS" in
  200) ok "GitHub Packages serves leash-core:$VERSION" ;;
  401|403|404)
    pending "GitHub Packages returned HTTP $STATUS; run the publish workflow (tag v$VERSION-core) or grant packages:read"
    exit 2
    ;;
  *) fail "unexpected GitHub Packages status: $STATUS" ;;
esac

step "A2 gate"
ok "leash-core:$VERSION is published and consumable"
