package io.github.nulled.transferguard.simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Usage:
 * <pre>
 *   seed                       30 days of history for the demo accounts
 *   scenarios                  run each of the five scenarios once and print the decisions
 *   run [minutes] [rps]        labelled traffic (90% normal, 10% scams), then precision and recall
 * </pre>
 */
public final class SimulatorMain {

    private SimulatorMain() {}

    public static void main(String[] args) throws Exception {
        SimConfig config = SimConfig.fromEnv();
        String command = args.length == 0 ? "scenarios" : args[0];
        ApiClient api = new ApiClient(config.apiUrl(), config.tokenUrl(), config.clientId(), config.clientSecret());
        ApiClient admin = new ApiClient(config.apiUrl(), config.tokenUrl(), config.adminClientId(),
                config.adminClientSecret());
        Scenarios scenarios = new Scenarios(api, admin, config);
        waitUntilReady(System.getenv().getOrDefault("TG_READY_URL",
                "http://localhost:8081/actuator/health/readiness"), Duration.ofMinutes(3));
        switch (command) {
            case "seed" -> new Seeder(config).seed();
            case "scenarios" -> {
                scenarios.ensureKnownMuleListed();
                boolean allOk = true;
                for (Scenario s : Scenario.values()) {
                    List<Observation> obs = scenarios.run(s);
                    String line = Report.scenarioLine(s, obs);
                    allOk &= line.endsWith(" OK");
                    System.out.println(line);
                }
                System.exit(allOk ? 0 : 1);
            }
            case "run" -> {
                int minutes = args.length > 1 ? Integer.parseInt(args[1]) : 5;
                int rps = args.length > 2 ? Integer.parseInt(args[2]) : 10;
                scenarios.ensureKnownMuleListed();
                String report = Report.markdown(run(scenarios, Duration.ofMinutes(minutes), rps));
                System.out.println(report);
                String out = System.getenv("SIM_REPORT");
                if (out != null && !out.isBlank()) {
                    Files.writeString(Path.of(out), report);
                }
            }
            default -> {
                System.err.println("Unknown command " + command + "; use seed, scenarios or run");
                System.exit(2);
            }
        }
    }

    /** The API migrates the schema on start, so seeding before it is ready would find no tables. */
    private static void waitUntilReady(String url, Duration timeout) throws InterruptedException {
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        Instant deadline = Instant.now().plus(timeout);
        boolean announced = false;
        while (Instant.now().isBefore(deadline)) {
            try {
                var r = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                        .timeout(Duration.ofSeconds(2)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    return;
                }
            } catch (java.io.IOException e) {
                // not listening yet
            }
            if (!announced) {
                System.out.println("Waiting for the API to be ready at " + url + " ...");
                announced = true;
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("API not ready after " + timeout + ": " + url);
    }

    private static List<Observation> run(Scenarios scenarios, Duration length, int rps) throws InterruptedException {
        List<Observation> all = Collections.synchronizedList(new ArrayList<>());
        Scenario[] scams = {Scenario.ACCOUNT_TAKEOVER, Scenario.MULE_RING, Scenario.VELOCITY_BURST, Scenario.KNOWN_MULE};
        Instant end = Instant.now().plus(length);
        long periodNanos = 1_000_000_000L / Math.max(1, rps);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            long next = System.nanoTime();
            while (Instant.now().isBefore(end)) {
                Scenario s = ThreadLocalRandom.current().nextInt(10) == 0
                        ? scams[ThreadLocalRandom.current().nextInt(scams.length)] : Scenario.NORMAL;
                pool.submit(() -> {
                    try {
                        all.addAll(scenarios.run(s));
                    } catch (RuntimeException e) {
                        System.err.println(s + ": " + e.getMessage());
                    }
                });
                next += periodNanos;
                long sleep = next - System.nanoTime();
                if (sleep > 0) {
                    TimeUnit.NANOSECONDS.sleep(sleep);
                }
            }
        }
        return all;
    }
}
