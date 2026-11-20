#!/usr/bin/env bash
# Run the CI and Jenkins-compatibility legs locally, in Docker, without a JDK
# or Maven on the host. See tools/sandbox/README.md.
#
#   tools/sandbox/sandbox.sh pinned        # ci.yml: the versions pinned in pom.xml, JDK 21
#   tools/sandbox/sandbox.sh jdk25         # jenkins-compat.yml: declared baseline on JDK 25
#   tools/sandbox/sandbox.sh lts           # jenkins-compat.yml: current LTS + newest BOM
#   tools/sandbox/sandbox.sh weekly        # jenkins-compat.yml: current weekly
#   tools/sandbox/sandbox.sh retry-check   # proves the MAVEN_ARGS retry setting is honoured
#   tools/sandbox/sandbox.sh shell         # interactive shell in the sandbox
#
# Extra arguments after the leg go to Maven, e.g. `sandbox.sh pinned -Dtest=DeploymentTest`.
#
# Environment:
#   SANDBOX_CPUS    CPUs given to the container (default 4, like a GitHub runner)
#   SANDBOX_MEMORY  memory limit (default 4g)
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
cpus=${SANDBOX_CPUS:-4}
memory=${SANDBOX_MEMORY:-4g}

leg=${1:-}
[ $# -gt 0 ] && shift

usage() {
    # The header comment, up to the first line that is not a comment.
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
    exit 2
}

# The retry setting comes from ci.yml rather than being repeated here, so the
# sandbox cannot drift from what CI does.
maven_args=$(sed -n 's/^  MAVEN_ARGS: //p' "$repo/.github/workflows/ci.yml")

image() {
    local jdk=$1 tag="deploy-dashboard-sandbox:jdk$1"
    docker build -q --build-arg "JDK=$jdk" -t "$tag" "$here" >/dev/null
    echo "$tag"
}

# The same resolution as the `versions` job of jenkins-compat.yml.
baseline() { sed -n 's:.*<jenkins.baseline>\(.*\)</jenkins.baseline>.*:\1:p' "$repo/pom.xml"; }
latest_lts() { curl -fsSL https://updates.jenkins.io/stable/latestCore.txt; }
latest_weekly() { curl -fsSL https://updates.jenkins.io/current/latestCore.txt; }
latest_bom() {
    curl -fsSL "https://repo.jenkins-ci.org/releases/io/jenkins/tools/bom/bom-$(baseline).x/maven-metadata.xml" |
        sed -n 's:.*<latest>\(.*\)</latest>.*:\1:p'
}

# Run Maven on the mounted checkout. The container runs as root so it can use
# the root-owned named volume; target/ is handed back to the caller on exit so
# a Linux host is not left with root-owned build output.
#
# One leg at a time per checkout: legs share target/.
run() {
    local jdk=$1
    shift
    local tag
    tag=$(image "$jdk")
    echo ">> JDK $jdk, ${cpus} CPUs, ${memory}, MAVEN_ARGS=${maven_args}" >&2
    echo ">> ./mvnw -B -ntp clean verify $*" >&2
    docker run --rm --cpus="$cpus" --memory="$memory" \
        -v "$repo":/work -v deploy-dashboard-m2:/root/.m2 \
        -e MAVEN_ARGS="$maven_args" -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" \
        "$tag" bash -c 'trap "chown -R $HOST_UID:$HOST_GID /work/target 2>/dev/null || true" EXIT
                        ./mvnw -B -ntp clean verify "$@"' sandbox "$@"
}

case "$leg" in
pinned)
    run 21 "$@"
    ;;
jdk25)
    run 25 "$@"
    ;;
lts)
    lts=$(latest_lts)
    bom=$(latest_bom)
    [[ "$lts" =~ ^2\.[0-9]+\.[0-9]+$ ]] || { echo "bad LTS version from updates.jenkins.io: '$lts'" >&2; exit 1; }
    [[ -n "$bom" ]] || { echo "no bom-$(baseline).x release found" >&2; exit 1; }
    run 21 "-Djenkins.version=$lts" "-Djenkins.bom.version=$bom" "$@"
    ;;
weekly)
    weekly=$(latest_weekly)
    [[ "$weekly" =~ ^2\.[0-9]+$ ]] || { echo "bad weekly version from updates.jenkins.io: '$weekly'" >&2; exit 1; }
    run 21 "-Djenkins.version=$weekly" "$@"
    ;;
retry-check)
    # Point every download at a server that always answers 429 and count how
    # often Maven asks for the first file: once plus the configured retries.
    # A throwaway Maven repository makes sure nothing comes from the cache, and
    # a 100 ms backoff keeps it fast.
    tag=$(image 21)
    expected=$(($(sed -n 's/.*retryHandler\.count=\([0-9]*\).*/\1/p' <<<"$maven_args") + 1))
    docker run --rm -v "$repo":/work:ro -v "$here":/sandbox:ro -e MAVEN_ARGS="$maven_args" "$tag" bash -c '
        python3 /sandbox/fake429.py 18429 >/tmp/hits.log 2>&1 &
        sleep 1
        ./mvnw -B -ntp -q -s /sandbox/settings-429.xml -Dmaven.repo.local=/tmp/m2 \
            -Daether.connector.http.retryHandler.interval=100 validate >/tmp/mvn.log 2>&1 || true
        first=$(head -1 /tmp/hits.log | cut -d" " -f3)
        echo "$(grep -c " $first\$" /tmp/hits.log) $first"' | {
        read -r got first
        echo "MAVEN_ARGS=$maven_args"
        echo "requests for $first: $got (expected $expected)"
        [ "$got" = "$expected" ] || { echo "retry setting NOT honoured" >&2; exit 1; }
        echo "retry setting honoured"
    }
    ;;
shell)
    tag=$(image 21)
    docker run --rm -it -v "$repo":/work -v deploy-dashboard-m2:/root/.m2 -e MAVEN_ARGS="$maven_args" "$tag" bash
    ;;
*)
    usage
    ;;
esac
