#!/usr/bin/env bash
# Chaos experiment (implementation plan, section 10). For each fail mode (OPEN, DEGRADED, CLOSED):
#   - set the fail mode through the API (If-Match),
#   - run k6 for 5 minutes at the configured rate,
#   - at minute 2 add 200 ms of latency to Redis for 60 s,
#   - at minute 3 cut the Redis connection for 60 s,
#   - export the k6 summary and the decision mix to chaos/results/<mode>/.
# Then write the postmortem from those numbers (docs/postmortem.md).
set -euo pipefail
cd "$(dirname "$0")/.."

TOXI=http://localhost:8474
API=http://localhost:8080/api/v1
PROM=http://localhost:9090
MODES=${MODES:-"OPEN DEGRADED CLOSED"}
RATE=${K6_RATE:-200}

token() {
  curl -sf -d grant_type=client_credentials -d client_id=local-admin -d client_secret=x \
    http://localhost:8090/transferguard/token | sed -E 's/.*"access_token":"([^"]+)".*/\1/'
}

set_fail_mode() {
  local t etag
  t=$(token)
  etag=$(curl -sfI -H "Authorization: Bearer $t" "$API/settings/engine" | awk -F': ' 'tolower($1)=="etag"{print $2}' | tr -d '\r')
  curl -sf -X PUT "$API/settings/engine" -H "Authorization: Bearer $t" -H "If-Match: $etag" \
    -H 'Content-Type: application/json' \
    -d "{\"allowMax\":39,\"challengeMax\":69,\"failMode\":\"$1\",\"degradedChallengeMinAmount\":\"1000.00\"}" >/dev/null
  echo "fail mode set to $1"
}

reset_proxy() {
  curl -sf -X DELETE "$TOXI/proxies/redis/toxics/latency" >/dev/null 2>&1 || true
  curl -sf -X POST "$TOXI/proxies/redis" -d '{"enabled":true}' >/dev/null
}

prom() { curl -sf --get "$PROM/api/v1/query" --data-urlencode "query=$1" | sed -E 's/.*"value":\[[^,]+,"([^"]+)".*/\1/'; }

for mode in $MODES; do
  out="chaos/results/$mode"
  mkdir -p "$out"
  reset_proxy
  set_fail_mode "$mode"
  start=$(date -u +%s)
  echo "[$mode] k6 started at $(date -u +%T)"
  K6_RATE=$RATE docker compose --profile load run --rm --no-deps \
    -e DURATION=5m k6 run --summary-export="/scripts/results/chaos-$mode.json" /scripts/screening.js \
    > "$out/k6.log" 2>&1 &
  k6pid=$!

  sleep 120
  echo "[$mode] +2:00 add 200 ms latency to Redis"
  curl -sf -X POST "$TOXI/proxies/redis/toxics" \
    -d '{"name":"latency","type":"latency","stream":"downstream","attributes":{"latency":200,"jitter":0}}' >/dev/null
  sleep 60
  echo "[$mode] +3:00 remove latency, cut Redis"
  curl -sf -X DELETE "$TOXI/proxies/redis/toxics/latency" >/dev/null
  curl -sf -X POST "$TOXI/proxies/redis" -d '{"enabled":false}' >/dev/null
  sleep 60
  echo "[$mode] +4:00 restore Redis"
  curl -sf -X POST "$TOXI/proxies/redis" -d '{"enabled":true}' >/dev/null
  wait $k6pid || echo "[$mode] k6 thresholds failed (expected during chaos); see $out/k6.log"
  end=$(date -u +%s)

  window="$((end - start))s"
  {
    echo "mode=$mode start=$start end=$end"
    echo "screenings_by_decision:"
    for d in ALLOW CHALLENGE HOLD; do
      echo "  $d $(prom "sum(increase(transferguard_screenings_total{decision=\"$d\"}[$window]))")"
    done
    echo "degraded $(prom "sum(increase(transferguard_screenings_total{degraded=\"true\"}[$window]))")"
    echo "p95_seconds $(prom "histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri=\"/api/v1/screenings\"}[$window])))")"
    echo "errors_5xx $(prom "sum(increase(http_server_requests_seconds_count{uri=\"/api/v1/screenings\",status=~\"5..\"}[$window]))")"
  } > "$out/summary.txt"
  mv "load-test/results/chaos-$mode.json" "$out/k6-summary.json" 2>/dev/null || true
  cat "$out/summary.txt"
done

set_fail_mode DEGRADED
echo "Done. Results in chaos/results/. Write them up in docs/postmortem.md."
