#!/usr/bin/env bash
# End-to-end smoke test against a running stack (make up). Uses real tokens from the mock OAuth2
# server, exercises all 19 operations plus the main failure paths, and uses fresh random accounts so
# it can run any number of times next to the demo data.
#
#   ./scripts/smoke-test.sh            (or: make smoke)
#   API=http://host:8080/api/v1 TOKEN_URL=... MGMT=... ./scripts/smoke-test.sh
#
# Needs curl and jq. Exit code is the number of failed checks (0 = all passed).
set -uo pipefail

API=${API:-http://localhost:8080/api/v1}
BASE=${API%/api/v1}
MGMT=${MGMT:-http://localhost:8081}
TOKEN_URL=${TOKEN_URL:-http://localhost:8090/transferguard/token}

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PASS=0
FAIL=0
STATUS=""

# ---- helpers ---------------------------------------------------------------------------------

green() { printf '\033[32m%s\033[0m' "$*"; }
red() { printf '\033[31m%s\033[0m' "$*"; }
section() { printf '\n\033[1m%s\033[0m\n' "$*"; }

pass() { PASS=$((PASS + 1)); printf '  %s %s\n' "$(green PASS)" "$1"; }
fail() {
  FAIL=$((FAIL + 1)); printf '  %s %s\n' "$(red FAIL)" "$1"
  [[ -s "$TMP/body" ]] && printf '       %s\n' "$(head -c 400 "$TMP/body")"
}

# call METHOD PATH TOKEN [BODY] [HEADER...]   → sets STATUS, body in $TMP/body, headers in $TMP/headers
call() {
  local method=$1 path=$2 token=$3 body=${4:-}
  shift 3; [[ $# -gt 0 ]] && shift
  local url=$path
  [[ $url == http* ]] || url="$API$path"
  local args=(-s -o "$TMP/body" -D "$TMP/headers" -w '%{http_code}' -X "$method" "$url"
              -H 'Content-Type: application/json')
  [[ -n $token ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n $body ]] && args+=(--data-binary "$body")
  for h in "$@"; do args+=(-H "$h"); done
  STATUS=$(curl "${args[@]}" 2>/dev/null || echo 000)
}

json() { jq -r "$1" "$TMP/body" 2>/dev/null; }
header() { grep -i "^$1:" "$TMP/headers" | head -1 | cut -d' ' -f2- | tr -d '\r'; }

# expect "description" STATUS [jq-condition]
expect() {
  local name=$1 want=$2 cond=${3:-}
  if [[ $STATUS != "$want" ]]; then fail "$name (HTTP $STATUS, expected $want)"; return; fi
  if [[ -n $cond ]] && [[ $(jq -r "$cond" "$TMP/body" 2>/dev/null) != "true" ]]; then
    fail "$name (HTTP $want, but not: $cond)"; return
  fi
  pass "$name"
}

token() {
  curl -sf -d grant_type=client_credentials -d "client_id=$1" -d client_secret=smoke "$TOKEN_URL" \
    | jq -r .access_token
}

acct() { printf '1402-%04d-%06d' $((RANDOM % 10000)) $(((RANDOM * 32768 + RANDOM) % 1000000)); }
txn() { printf 'SMOKE-%s-%s' "$(date +%s)" "$RANDOM$RANDOM"; }
now() { date -u +%Y-%m-%dT%H:%M:%SZ; }
minutes_ago() { date -u -d "-$1 min" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v-"$1"M +%Y-%m-%dT%H:%M:%SZ; }

# transfer TXN SOURCE PAYEE AMOUNT [DEVICE] [REQUESTED_AT]
transfer() {
  jq -nc --arg t "$1" --arg s "$2" --arg p "$3" --arg a "$4" --arg d "${5:-dev-smoke}" --arg r "${6:-$(now)}" \
    '{transactionId:$t, sourceAccount:$s, payeeAccount:$p, amount:$a, currency:"MYR", channel:"MOBILE",
      deviceId:$d, requestedAt:$r}'
}

has_reason() { jq -e --arg c "$1" '[.reasons[].code] | index($c) != null' "$TMP/body" >/dev/null 2>&1; }

# ---- 0. stack is up ------------------------------------------------------------------------------

section "0. Stack health"
call GET "$MGMT/actuator/health/readiness" ""
expect "readiness is UP (PostgreSQL reachable)" 200 '.status == "UP"'
call GET "$MGMT/actuator/health/liveness" ""
expect "liveness is UP" 200
call GET "$MGMT/actuator/prometheus" ""
if [[ $STATUS == 200 ]] && grep -q '^resilience4j_circuitbreaker_state' "$TMP/body"; then
  pass "Prometheus metrics exported"
else fail "Prometheus metrics exported"; fi
call GET "$BASE/openapi.yaml" ""
expect "contract served at /openapi.yaml" 200
call GET "$BASE/swagger-ui/index.html" ""
expect "Swagger UI served" 200

ADMIN=$(token local-admin)
ANALYST=$(token local-analyst)
SIM=$(token transferguard-simulator)
if [[ -z $ADMIN || $ADMIN == null || -z $ANALYST || -z $SIM ]]; then
  echo "$(red FATAL) could not get tokens from $TOKEN_URL (is mock-oauth2 running?)"; exit 1
fi
pass "tokens issued by the mock OAuth2 server (admin, analyst, simulator)"

# ---- 1. authentication and scopes ------------------------------------------------------------------

section "1. Authentication and scopes"
SRC=$(acct); PAYEE=$(acct)
call POST /screenings "" "$(transfer "$(txn)" "$SRC" "$PAYEE" 10.00)"
expect "no token → 401 problem" 401 '.type | endswith("/unauthorized")'
call POST /screenings "not.a.jwt" "$(transfer "$(txn)" "$SRC" "$PAYEE" 10.00)"
expect "garbage token → 401" 401
call POST /screenings "$ANALYST" "$(transfer "$(txn)" "$SRC" "$PAYEE" 10.00)"
expect "analyst cannot screen (no screening.write) → 403" 403 '.type | endswith("/forbidden")'
call GET /rules "$SIM"
expect "simulator cannot read rules (no rules.admin) → 403" 403
call GET /audit "$ANALYST"
expect "analyst cannot read the audit log (no audit.read) → 403" 403

# ---- 2. screening, idempotency, validation --------------------------------------------------------

section "2. Screening, idempotency and validation"
T1=$(txn); BODY=$(transfer "$T1" "$SRC" "$PAYEE" 120.00)
call POST /screenings "$SIM" "$BODY"
expect "small transfer → 201 ALLOW" 201 '.decision == "ALLOW" and .degraded == false'
SID=$(json .screeningId)
[[ $(header Location) == "/api/v1/screenings/$SID" ]] && pass "Location header points at the screening" \
  || fail "Location header (got '$(header Location)')"
call POST /screenings "$SIM" "$BODY"
expect "same request again → 200 replay" 200 ".screeningId == \"$SID\""
[[ $(header Idempotent-Replay) == "true" ]] && pass "Idempotent-Replay: true" || fail "Idempotent-Replay header"
call POST /screenings "$SIM" "$(transfer "$T1" "$SRC" "$PAYEE" 121.00)"
expect "same transactionId, different body → 422" 422 '.type | endswith("/idempotency-mismatch")'
call POST /screenings "$SIM" '{"transactionId":"bad id","amount":"1","currency":"USD"}'
expect "invalid body → 400 with field errors" 400 '(.errors | length) > 0'
call POST /screenings "$SIM" "$(transfer "$(txn)" "$SRC" "$PAYEE" 10.00 dev-smoke "$(minutes_ago 10)")"
expect "requestedAt 10 minutes old → 400" 400 '.errors[0].field == "requestedAt"'
call POST /screenings "$SIM" "$(transfer "$(txn)" "$SRC" "$PAYEE" 10.00 "$(head -c 17000 /dev/zero | tr '\0' x)")"
expect "body over 16 KB → 413" 413

call GET "/screenings/$SID" "$SIM"
expect "GET screening with its request" 200 ".request.amount == \"120.00\""
call GET "/screenings/00000000-0000-4000-8000-000000000000" "$SIM"
expect "unknown screening → 404" 404

# ---- 3. outcomes teach known payees ----------------------------------------------------------------

section "3. Outcomes (R2 learns from completed transfers only)"
P2=$(acct)
call POST /screenings "$SIM" "$(transfer "$(txn)" "$SRC" "$P2" 3500.00)"
expect "RM 3,500 to a new payee → R2 fires" 201
has_reason R2_NEW_PAYEE_HIGH_AMOUNT && pass "reason R2_NEW_PAYEE_HIGH_AMOUNT" || fail "reason R2_NEW_PAYEE_HIGH_AMOUNT"
S2=$(json .screeningId)
OUTCOME=$(jq -nc --arg t "$(now)" '{outcome:"COMPLETED", occurredAt:$t}')
call POST "/screenings/$S2/outcome" "$SIM" "$OUTCOME"
expect "outcome COMPLETED → 204" 204
call POST "/screenings/$S2/outcome" "$SIM" "$OUTCOME"
expect "same outcome again → 204 (idempotent)" 204
call POST "/screenings/$S2/outcome" "$SIM" "$(jq -nc --arg t "$(now)" '{outcome:"CANCELLED", occurredAt:$t}')"
expect "changing a recorded outcome → 409" 409
call POST /screenings "$SIM" "$(transfer "$(txn)" "$SRC" "$P2" 3500.00)"
expect "same payee after completion → 201" 201
has_reason R2_NEW_PAYEE_HIGH_AMOUNT && fail "R2 should not fire for a known payee" || pass "payee is now known (no R2)"

# ---- 4. events: R6 new device and R5 pass-through --------------------------------------------------

section "4. Events (R6 new device, R5 mule pass-through)"
VICTIM=$(acct)
call POST /events/security "$SIM" "$(jq -nc --arg a "$VICTIM" --arg t "$(minutes_ago 12)" \
  '{eventId:"EV-smoke-1", account:$a, type:"DEVICE_CHANGED", deviceId:"dev-new", occurredAt:$t}')"
expect "security event DEVICE_CHANGED → 202" 202
call POST /events/security "$SIM" "$(jq -nc --arg a "$VICTIM" --arg t "$(now)" \
  '{eventId:"EV-smoke-2", account:$a, type:"DEVICE_CHANGED", occurredAt:$t}')"
expect "DEVICE_CHANGED without deviceId → 400" 400
call POST /screenings "$SIM" "$(transfer "$(txn)" "$VICTIM" "$(acct)" 4800.00 dev-new)"
expect "transfer 12 minutes after a device change → 201" 201
has_reason R6_NEW_DEVICE && pass "reason R6_NEW_DEVICE: $(jq -r '.reasons[] | select(.code=="R6_NEW_DEVICE") | .message' "$TMP/body")" \
  || fail "reason R6_NEW_DEVICE"

MULE=$(acct)
for i in 1 2 3 4; do
  call POST /events/inbound-credits "$SIM" "$(jq -nc --arg a "$MULE" --arg s "$(acct)" --arg c "CR-smoke-$MULE-$i" --arg t "$(now)" \
    '{creditId:$c, account:$a, senderAccount:$s, amount:"2500.00", currency:"MYR", receivedAt:$t}')"
  [[ $STATUS == 202 ]] || fail "inbound credit $i (HTTP $STATUS)"
done
[[ $STATUS == 202 ]] && pass "4 inbound credits from 4 senders → 202"
call POST /screenings "$SIM" "$(transfer "$(txn)" "$MULE" "$(acct)" 9000.00 dev-mule)"
expect "mule sends 90% onward → HOLD" 201 '.decision == "HOLD"'
has_reason R5_PASS_THROUGH && pass "reason R5_PASS_THROUGH" || fail "reason R5_PASS_THROUGH"
MULE_CASE=$(json .caseId)

# ---- 5. watchlist (R7) -------------------------------------------------------------------------------

section "5. Watchlist (R7)"
MULE_PAYEE=$(acct)
WL='{"source":"POLICE_REPORT","reason":"Smoke test: reported mule"}'
call PUT "/watchlist/$MULE_PAYEE" "$ADMIN" "$WL"
expect "add to watchlist → 201" 201
call PUT "/watchlist/$MULE_PAYEE" "$ADMIN" '{"source":"NFCC","reason":"Smoke test: also NFCC"}'
expect "update entry → 200" 200 '.source == "NFCC"'
call PUT "/watchlist/not-an-account" "$ADMIN" "$WL"
expect "bad account number → 400" 400
call GET "/watchlist?size=100" "$ADMIN"
expect "list watchlist" 200 "[.items[].accountNo] | index(\"$MULE_PAYEE\") != null"
call POST /screenings "$SIM" "$(transfer "$(txn)" "$(acct)" "$MULE_PAYEE" 50.00)"
expect "transfer to a watchlisted payee → HOLD" 201 '.decision == "HOLD" and ([.reasons[].code] | index("R7_WATCHLIST") != null)'
WL_CASE=$(json .caseId)
call DELETE "/watchlist/$MULE_PAYEE" "$ADMIN"
expect "remove from watchlist → 204" 204
call DELETE "/watchlist/$MULE_PAYEE" "$ADMIN"
expect "remove again → 404" 404

# ---- 6. velocity (R1) --------------------------------------------------------------------------------

section "6. Velocity (R1)"
BURST=$(acct)
for i in 1 2 3 4 5; do call POST /screenings "$SIM" "$(transfer "$(txn)" "$BURST" "$(acct)" 15.00)"; done
has_reason R1_VELOCITY && fail "R1 fired on the 5th attempt" || pass "5th attempt in 10 minutes: no R1"
call POST /screenings "$SIM" "$(transfer "$(txn)" "$BURST" "$(acct)" 15.00)"
expect "6th attempt → CHALLENGE" 201 '.decision == "CHALLENGE"'
has_reason R1_VELOCITY && pass "reason R1_VELOCITY: $(jq -r '.reasons[0].message' "$TMP/body")" || fail "reason R1_VELOCITY"

# ---- 7. cases ------------------------------------------------------------------------------------------

section "7. Cases"
call GET "/cases?status=OPEN&size=100" "$ANALYST"
expect "analyst lists open cases" 200 "[.items[].caseId] | index(\"$WL_CASE\") != null"
call GET "/cases/$WL_CASE" "$ANALYST"
expect "case detail with screening and notes" 200 ".screening.request.payeeAccount == \"$MULE_PAYEE\""
call PATCH "/cases/$WL_CASE" "$ANALYST" '{"assignee":"local-analyst"}'
expect "assign → 200" 200 '.assignee == "local-analyst"'
call PATCH "/cases/$WL_CASE" "$ANALYST" '{"status":"FALSE_POSITIVE"}'
expect "resolve without a note → 400" 400
call PATCH "/cases/$WL_CASE" "$ANALYST" '{"status":"FALSE_POSITIVE","resolutionNote":"Smoke test","addPayeeToWatchlist":true}'
expect "addPayeeToWatchlist with FALSE_POSITIVE → 400" 400
call PATCH "/cases/$WL_CASE" "$ANALYST" '{"status":"FALSE_POSITIVE","resolutionNote":"Smoke test: customer known to payee"}'
expect "resolve → 200" 200 '.status == "FALSE_POSITIVE" and .resolvedAt != null'
call PATCH "/cases/$WL_CASE" "$ANALYST" '{"assignee":"someone-else"}'
expect "update a resolved case → 409 (final)" 409
call POST "/cases/$WL_CASE/notes" "$ANALYST" '{"note":"Smoke test: correction as a note"}'
expect "add note → 201" 201 '.noteId > 0'
if [[ -n $MULE_CASE && $MULE_CASE != null ]]; then
  call PATCH "/cases/$MULE_CASE" "$ANALYST" '{"status":"CONFIRMED_FRAUD","resolutionNote":"Smoke test mule","addPayeeToWatchlist":true}'
  expect "confirm fraud and watchlist the payee in one request → 200" 200 '.status == "CONFIRMED_FRAUD"'
fi
call GET "/cases/CASE-999999999" "$ANALYST"
expect "unknown case → 404" 404

# ---- 8. rules and settings ---------------------------------------------------------------------------

section "8. Rules and engine settings"
call GET /rules "$ADMIN"
expect "list rules → 7" 200 'length == 7'
call GET /rules/R1_VELOCITY "$ADMIN"
expect "get R1" 200
ETAG=$(header ETag)
RULE=$(jq -c '{enabled, weight, params}' "$TMP/body")
[[ -n $ETAG ]] && pass "ETag $ETAG" || fail "ETag header"
call PUT /rules/R1_VELOCITY "$ADMIN" "$RULE"
expect "update without If-Match → 428" 428
call PUT /rules/R1_VELOCITY "$ADMIN" "$RULE" 'If-Match: "99999"'
expect "stale If-Match → 412" 412
call PUT /rules/R1_VELOCITY "$ADMIN" '{"enabled":true,"weight":40,"params":{"maxCount":0,"window":"PT10M"}}' "If-Match: $ETAG"
expect "invalid params → 400 naming the field" 400 '.errors[0].field == "params.maxCount"'
call PUT /rules/R1_VELOCITY "$ADMIN" "$RULE" "If-Match: $ETAG"
expect "update with If-Match → 200 (same values)" 200
[[ $(header ETag) != "$ETAG" ]] && pass "ETag moved on to $(header ETag)" || fail "ETag did not change"
call GET /rules/R9_NOPE "$ADMIN"
expect "unknown rule → 404" 404

call GET /settings/engine "$ADMIN"
expect "get engine settings" 200 '.allowMax < .challengeMax'
SETTINGS=$(jq -c . "$TMP/body"); SETAG=$(header ETag)
call PUT /settings/engine "$ADMIN" '{"allowMax":70,"challengeMax":69,"failMode":"DEGRADED"}' "If-Match: $SETAG"
expect "allowMax >= challengeMax → 400" 400
call PUT /settings/engine "$ADMIN" "$SETTINGS" "If-Match: $SETAG"
expect "update settings (same values) → 200" 200

# ---- 9. audit ----------------------------------------------------------------------------------------

section "9. Audit"
call GET "/audit?entity=rule:R1_VELOCITY" "$ADMIN"
expect "rule change is audited" 200 '.items[0].action == "RULE_UPDATED"'
call GET "/audit?entity=case:$WL_CASE" "$ADMIN"
expect "case changes are audited" 200 '[.items[].action] == ["CASE_NOTE_ADDED","CASE_RESOLVED","CASE_ASSIGNED"]'
call GET "/audit?entity=watchlist:$MULE_PAYEE" "$ADMIN"
expect "watchlist changes are audited" 200 '[.items[].action] == ["WATCHLIST_REMOVED","WATCHLIST_UPDATED","WATCHLIST_ADDED"]'
call GET /audit/verify "$ADMIN"
expect "hash chain verifies" 200 '.valid == true and .entriesChecked > 0'

# ---- summary -----------------------------------------------------------------------------------------

printf '\n%s passed, %s failed\n' "$(green "$PASS")" "$( ((FAIL)) && red "$FAIL" || echo 0)"
exit "$FAIL"
