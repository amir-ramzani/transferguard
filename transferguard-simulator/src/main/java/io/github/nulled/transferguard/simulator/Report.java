package io.github.nulled.transferguard.simulator;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Precision and recall per rule, from ground-truth labels. The data is synthetic: treat these
 * numbers as a regression baseline, not real-world accuracy.
 *
 * <ul>
 *   <li>Rule precision: of the transfers the rule fired on, the share that were fraud.</li>
 *   <li>Rule recall: of the fraud transfers in the scenarios the rule targets, the share it fired on.</li>
 *   <li>Decision recall: fraud transfers that were not ALLOWed. False-positive rate: legitimate ones that were.</li>
 * </ul>
 */
public final class Report {

    static final List<String> RULES = List.of("R1_VELOCITY", "R2_NEW_PAYEE_HIGH_AMOUNT", "R3_AMOUNT_ANOMALY",
            "R4_ODD_HOURS", "R5_PASS_THROUGH", "R6_NEW_DEVICE", "R7_WATCHLIST");

    private Report() {}

    public static String markdown(List<Observation> all) {
        List<Observation> obs = all.stream().filter(o -> o.status() == 201 || o.status() == 200).toList();
        StringBuilder sb = new StringBuilder();
        long fraud = obs.stream().filter(Observation::fraud).count();
        sb.append("Transfers: %d (%d fraud, %d legitimate), errors: %d%n%n".formatted(obs.size(), fraud,
                obs.size() - fraud, all.size() - obs.size()));
        sb.append("| Rule | Fired | Precision | Recall (target scenarios) |\n|---|---:|---:|---:|\n");
        for (String rule : RULES) {
            List<Observation> fired = obs.stream().filter(o -> o.reasons().contains(rule)).toList();
            long tp = fired.stream().filter(Observation::fraud).count();
            List<Observation> targets = obs.stream()
                    .filter(o -> o.fraud() && o.scenario().targetRules().contains(rule)).toList();
            long caught = targets.stream().filter(o -> o.reasons().contains(rule)).count();
            sb.append("| %s | %d | %s | %s |%n".formatted(rule, fired.size(), pct(tp, fired.size()),
                    pct(caught, targets.size())));
        }
        long fraudFlagged = obs.stream().filter(o -> o.fraud() && !"ALLOW".equals(o.decision())).count();
        long legit = obs.size() - fraud;
        long legitFlagged = obs.stream().filter(o -> !o.fraud() && !"ALLOW".equals(o.decision())).count();
        sb.append("%nDecision recall (fraud not allowed): %s; false-positive rate (legitimate not allowed): %s%n"
                .formatted(pct(fraudFlagged, fraud), pct(legitFlagged, legit)));
        sb.append("\n| Scenario | Transfers | ALLOW | CHALLENGE | HOLD |\n|---|---:|---:|---:|---:|\n");
        for (Scenario s : Scenario.values()) {
            List<Observation> in = obs.stream().filter(o -> o.scenario() == s).toList();
            if (in.isEmpty()) {
                continue;
            }
            sb.append("| %s | %d | %d | %d | %d |%n".formatted(s, in.size(), count(in, "ALLOW"),
                    count(in, "CHALLENGE"), count(in, "HOLD")));
        }
        long[] lat = obs.stream().mapToLong(Observation::latencyMs).sorted().toArray();
        if (lat.length > 0) {
            sb.append("%nClient-side latency: p50 %d ms, p95 %d ms, p99 %d ms%n".formatted(
                    lat[(int) (lat.length * 0.50)], lat[Math.min(lat.length - 1, (int) (lat.length * 0.95))],
                    lat[Math.min(lat.length - 1, (int) (lat.length * 0.99))]));
        }
        return sb.toString();
    }

    public static String scenarioLine(Scenario s, List<Observation> obs) {
        Observation last = obs.get(obs.size() - 1);
        boolean ok = last.decision().equals(s.expectedFinalDecision());
        return "%-17s %-9s (expected %-9s) score %3d  %s  %s".formatted(s, last.decision(), s.expectedFinalDecision(),
                last.score(), last.reasons().stream().collect(Collectors.joining(", ")), ok ? "OK" : "UNEXPECTED");
    }

    private static long count(List<Observation> obs, String decision) {
        return obs.stream().filter(o -> decision.equals(o.decision())).count();
    }

    private static String pct(long n, long d) {
        return d == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f%%", 100.0 * n / d);
    }

}
