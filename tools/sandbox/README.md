# Docker build sandbox

Runs the same Maven legs as CI on your machine, inside Docker. You do not need
a JDK or Maven on the host — only Docker.

```bash
tools/sandbox/sandbox.sh pinned        # ci.yml: the versions pinned in pom.xml, JDK 21
tools/sandbox/sandbox.sh jdk25         # jenkins-compat.yml: declared baseline on JDK 25
tools/sandbox/sandbox.sh lts           # jenkins-compat.yml: current LTS + newest plugin BOM
tools/sandbox/sandbox.sh weekly        # jenkins-compat.yml: current weekly
tools/sandbox/sandbox.sh retry-check   # proves the Maven Central retry setting is honoured
tools/sandbox/sandbox.sh shell         # interactive shell in the sandbox
```

Arguments after the leg go to Maven:

```bash
tools/sandbox/sandbox.sh pinned -Dtest=DeploymentTest
```

Each leg runs `./mvnw -B -ntp clean verify` on your checkout, so the result,
including the `.hpi`, is in `target/` as usual.

## How it matches CI

- **JDK.** Temurin 21 (or 25 for `jdk25`) on Ubuntu 24.04, the same as
  `actions/setup-java` on the pinned `ubuntu-24.04` runners.
- **Versions.** `lts` and `weekly` resolve "current" from the same feeds, in
  the same way, as the `versions` job of `.github/workflows/jenkins-compat.yml`.
- **Maven Central retries.** `MAVEN_ARGS` is read from
  `.github/workflows/ci.yml`, so the sandbox cannot drift from CI.
  `retry-check` points Maven at a local server that always answers 429 and
  checks that Maven asks for the first file exactly 1 + the configured number
  of times.
- **Size.** The container gets 4 CPUs and 4 GB by default, like a GitHub
  runner. This matters: the POM sets `forkCount=0.5C`, so on an 18-core
  machine an unrestricted build starts 9 test JVMs of 768 MB each and Docker
  kills them (exit code 137). Change the size with `SANDBOX_CPUS` and
  `SANDBOX_MEMORY`.

## Notes

- Run one leg at a time per checkout, because the legs share `target/`. To run
  legs in parallel, use one `git worktree` for each.
- Downloads are cached in the Docker volume `deploy-dashboard-m2`. Remove it
  with `docker volume rm deploy-dashboard-m2` to start from an empty cache.
- The image is tagged `deploy-dashboard-sandbox:jdk21` (or `:jdk25`) and is
  built the first time it is needed.
