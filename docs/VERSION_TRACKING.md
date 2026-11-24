# Version Tracking

Every completed phase gets a versioned artifact, a git tag, and one reproducible verification
command. A phase is not complete because code merged; it is complete when its gate passes.

| Phase | Git tag | Artifact version | Where it lives | Verification |
|---|---|---|---|---|
| A1 | `phase-a1-done` | `leash-core:1.0.0-SNAPSHOT`, `leash-backend:1.0.0-SNAPSHOT` | Local Maven reactor | `scripts/gates/verify_a1.sh` |
| A2 | `v1.0.0-core` + `phase-a2-done` | `leash-core:1.0.0` | GitHub Packages | `scripts/gates/verify_a2.sh` |
| A3 | `phase-a3-done` | `leash-backend:1.0.0` | Render Docker + Postgres | `LEASH_DEMO_URL=… scripts/gates/verify_a3.sh` |
| C1 | `phase-c1-done` | `leash-sidecar:1.0.0` | Docker image | `scripts/gates/verify_c1.sh` |
| C2 | `phase-c2-done` | `leash-sidecar:1.1.0` | Docker image | `scripts/gates/verify_c2.sh` |
| C3 | `phase-c3-done` | `leash-python:1.0.0`, `leash-ts:1.0.0`, `leash-go:1.0.0` | PyPI, npm, Go modules | `scripts/gates/verify_c3.sh` |
| C4 | `phase-c4-done` | decision record | `docs/DECISION_C4.md` | `scripts/gates/verify_c4.sh` |
| B1 | `phase-b1-done` | `leash-platform:1.0.0` | Kubernetes | `scripts/gates/verify_b1.sh` |
| B2 | `phase-b2-done` | `leash-platform:1.1.0` | Kubernetes | `scripts/gates/verify_b2.sh` |
| B3 | `phase-b3-done` | `leash-platform:1.2.0` | Kubernetes | `scripts/gates/verify_b3.sh` |
| B4 | `phase-b4-done` + `v1.0.0-launch` | `leash-platform:1.0.0` (launch) | Production | `scripts/gates/verify_b4.sh` |

## Maven coordinates

```xml
<dependency>
  <groupId>com.leash</groupId>
  <artifactId>leash-core</artifactId>
  <version>1.0.0</version>
</dependency>
```

Repository for consumers (snapshots and releases):

```xml
<repository>
  <id>github</id>
  <url>https://maven.pkg.github.com/anupkumarsharma9705/leash</url>
</repository>
```

GitHub Packages requires authentication even for public packages. Use a token with
`read:packages`; CI uses `GITHUB_TOKEN` with `packages: read`.

## Release procedure

1. Run the phase gate until it exits `0`.
2. Update `docs/PROGRESS.md` with the gate output and date.
3. Update the table above if the artifact version changed.
4. Commit as `chore(release): <phase> gate passed`.
5. Create the tag: `git tag -a <tag> -m "<phase>: <evidence>"`.
6. Push the branch and tag: `git push origin main --follow-tags`.
7. For A2, the tag also triggers `Publish leash-core`; check the Actions run is green.
