# ADR 001: Rules before machine learning

**Status:** Accepted · **Date:** 2024-11-02

## Context
TransferGuard must explain every decision to analysts, to the bank's regulators (Bank Negara Malaysia)
and, through the bank's own channels, to customers. There is no labelled fraud data yet: the only
labels will come from analysts resolving cases (`CONFIRMED_FRAUD` / `FALSE_POSITIVE`).

## Decision
Score transfers with seven explicit, tunable rules (R1–R7). Each rule is data (`rule` table: enabled,
weight, JSON parameters) and returns a reason with the numbers filled in. The score is the sum of the
weights of the rules that fired; decision bands (`engine_setting`) map it to ALLOW, CHALLENGE or HOLD.

## Consequences
- Every decision lists every rule that fired, with points and a message (`screening_reason`).
- Analysts tune rules through the API, with an audit trail, without a redeploy.
- Case resolutions accumulate the labelled data a model would need. A model can later become one more
  rule (an `R8` with its own weight), so it stays explainable at the decision level.
- Rules are blunt: the precision/recall table from the simulator is the baseline any model must beat.
