# Postmortem: Redis latency and outage under load (chaos experiment)

> Template. Fill it in from `chaos/results/<mode>/summary.txt` and the k6 summaries after running
> `make up && make demo && make chaos`. Blameless format. Numbers below are placeholders until then.

## Summary
During a 200 rps load test, Redis got 200 ms of added latency for 60 s (minute 2) and was then cut off
for 60 s (minute 3). The experiment ran once per fail mode: OPEN, DEGRADED and CLOSED.

## Impact

| Fail mode | Screenings | p95 (ms) | 5xx | ALLOW / CHALLENGE / HOLD | Degraded | Time to full strength after recovery |
|---|---:|---:|---:|---|---:|---|
| OPEN | _tbd_ | _tbd_ | _tbd_ | _tbd_ | _tbd_ | _tbd_ |
| DEGRADED | _tbd_ | _tbd_ | _tbd_ | _tbd_ | _tbd_ | _tbd_ |
| CLOSED | _tbd_ | _tbd_ | _tbd_ | _tbd_ | _tbd_ | _tbd_ |

## Timeline (UTC)
- T+0:00 load starts
- T+2:00 200 ms latency toxic added → commands exceed the 50 ms timeout → breaker opens after 20 calls
- T+3:00 latency removed, connection cut
- T+4:00 connection restored → breaker half-open after 10 s → closes → rebuild job runs
- T+5:00 load ends

## Root cause
Injected. The interesting question is how each fail mode trades fraud exposure against friction.

## What went well
_tbd_

## What went badly
_tbd_ (expected: R1 under-counts until the rebuild finishes; the first 20 calls after the fault pay the 50 ms timeout)

## Actions
_tbd_
