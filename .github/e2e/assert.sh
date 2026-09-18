#!/usr/bin/env bash
# Drives the seeded Jenkins and asserts what a user would see.
#
# Every check here is something the in-JVM test harness cannot tell us: that the
# packaged .hpi renders its Jelly views inside a stock Jenkins, that the
# dashboard reports the right release, and that the redirecting action survives
# a real HTTP round trip with its query string.
set -euo pipefail

J=http://localhost:8080

fail() { echo "::error::$*"; exit 1; }

echo "--- building deploy-app ---"
curl -fsS -X POST "$J/job/deploy-app/build?delay=0sec" >/dev/null

for i in $(seq 1 60); do
  status=$(curl -fsS "$J/job/deploy-app/1/api/json?tree=building,result" 2>/dev/null || echo '{}')
  case "$status" in
    *'"building":false'*) break ;;
  esac
  sleep 5
done
echo "$status" | grep -q '"result":"SUCCESS"' \
  || fail "the seeded pipeline did not succeed: $status"

echo "--- building legacy-app (freestyle) ---"
curl -fsS -X POST "$J/job/legacy-app/build?delay=0sec" >/dev/null

for i in $(seq 1 60); do
  status=$(curl -fsS "$J/job/legacy-app/1/api/json?tree=building,result" 2>/dev/null || echo '{}')
  case "$status" in
    *'"building":false'*) break ;;
  esac
  sleep 5
done
echo "$status" | grep -q '"result":"SUCCESS"' \
  || fail "the seeded freestyle job did not succeed: $status"

echo "--- the dashboard view ---"
html=$(curl -fsS "$J/view/deployments/")

# A freestyle deployment is recorded by the same build step and has to be shown
# by the same view. It used to be silently dropped.
grep -q 'legacy-app' <<<"$html" \
  || fail "the freestyle job is missing from the dashboard entirely"
grep -q '2\.7\.0' <<<"$html" \
  || fail "the freestyle job's release is not shown"
grep -qw 'qa' <<<"$html" \
  || fail "the freestyle job's environment is not shown"

grep -q '3\.1\.4' <<<"$html" || fail "the dashboard does not show the deployed release 3.1.4"
grep -q 'staging'  <<<"$html" || fail "the dashboard does not show the staging environment"

# The bug this release fixes: one build deploying to two environments used to
# show only the first, because the view read run.getAction() (singular).
grep -q 'production' <<<"$html" \
  || fail "only one environment is shown; a build that deploys to several must list them all"

# Environment order must be deterministic, not hash order.
#
# Read off the toggles rather than by grepping the whole page for the names:
# there is exactly one toggle per environment row, in render order, whereas a
# raw substring search counts every other mention too. It used to work only
# because each name happened to appear once per row, and broke the moment the
# markup carried the name anywhere else.
order=$(grep -oE 'data-popup-title="[^"]*"' <<<"$html" \
  | sed 's/.*| //; s/"$//' \
  | head -2 \
  | tr '\n' ' ')
if [ "$order" != "production staging " ]; then
  fail "environments are not in a deterministic order, got: [$order]"
fi

# JENKINS-74429: the view must not ship inline javascript: URLs.
grep -q 'javascript:toggle' <<<"$html" \
  && fail "the view still emits inline javascript: URLs"
grep -q 'edb-popup-toggle' <<<"$html" \
  || fail "the CSP-safe modal toggle is missing"

# Collapsing is client-side, so the served HTML always carries every row. A
# reader without scripts, and anything scraping the page, must still see them.
grep -q 'edb-toggle' <<<"$html" \
  || fail "the per-job collapse toggle is missing"
[ "$(grep -c 'class="edb-job"' <<<"$html")" -ge 1 ] \
  || fail "no per-job summary row was rendered"
[ "$(grep -c 'class="edb-job-envs"' <<<"$html")" -ge 1 ] \
  || fail "no per-job environment section was rendered"

# The dashboard must render on core's current table styling, not the legacy
# pane/bigtable classes core only keeps for compatibility.
grep -q 'jenkins-table' <<<"$html" \
  || fail "the dashboard is not using core's current table styling"
grep -qE 'class="[^"]*\b(bigtable|stripped-odd)\b' <<<"$html" \
  && fail "the dashboard still carries legacy core table classes"

echo "--- the buildAddUrl redirect ---"
link=$(python3 - <<'PY'
import re, urllib.request
html = urllib.request.urlopen("http://localhost:8080/job/deploy-app/1/").read().decode()
m = re.search(r'href="([^"]*deploy-link-[0-9a-f]+)"', html)
print(m.group(1) if m else "")
PY
)
[ -n "$link" ] || fail "no deploy-link action was rendered on the build page"

# The trailing slash matters: without it Stapler answers with its own 302 to
# add one, and we would read that instead of the plugin's redirect.
location=$(curl -fsS -o /dev/null -D - "$J${link#http://localhost:8080}/" \
  | tr -d '\r' | awk 'tolower($1)=="location:"{print $2}')
[ -n "$location" ] || fail "the deploy-link action did not redirect"
grep -q 'version=3.1.4' <<<"$location" \
  || fail "the redirect dropped the query string: $location"
grep -q 'region=eu-west-1' <<<"$location" \
  || fail "the redirect dropped part of the query string: $location"

echo "e2e OK: dashboard shows both environments at 3.1.4, and the link redirects with its query string intact"
