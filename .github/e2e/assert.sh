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

echo "--- the dashboard view ---"
html=$(curl -fsS "$J/view/deployments/")

grep -q '3\.1\.4' <<<"$html" || fail "the dashboard does not show the deployed release 3.1.4"
grep -q 'staging'  <<<"$html" || fail "the dashboard does not show the staging environment"

# The bug this release fixes: one build deploying to two environments used to
# show only the first, because the view read run.getAction() (singular).
grep -q 'production' <<<"$html" \
  || fail "only one environment is shown; a build that deploys to several must list them all"

# Environment order must be deterministic, not hash order.
if [ "$(grep -o -E 'production|staging' <<<"$html" | head -2 | tr '\n' ' ')" != "production staging " ]; then
  fail "environments are not in a deterministic order"
fi

# JENKINS-74429: the view must not ship inline javascript: URLs.
grep -q 'javascript:toggle' <<<"$html" \
  && fail "the view still emits inline javascript: URLs"
grep -q 'edb-popup-toggle' <<<"$html" \
  || fail "the CSP-safe modal toggle is missing"

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
