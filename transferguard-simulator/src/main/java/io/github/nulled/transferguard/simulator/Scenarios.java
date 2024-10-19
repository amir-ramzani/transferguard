package io.github.nulled.transferguard.simulator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates labelled traffic. Time is compressed: everything happens now, because the API rejects
 * requests timed more than 5 minutes from server time. R4 therefore only fires when you run the
 * simulator between 00:00 and 06:00 Kuala Lumpur time.
 */
public final class Scenarios {

    private final ApiClient api;
    private final ApiClient admin;
    private final SimConfig config;

    public Scenarios(ApiClient api, ApiClient admin, SimConfig config) {
        this.api = api;
        this.admin = admin;
        this.config = config;
    }

    public List<Observation> run(Scenario scenario) {
        return switch (scenario) {
            case NORMAL -> normal();
            case ACCOUNT_TAKEOVER -> takeover();
            case MULE_RING -> muleRing();
            case VELOCITY_BURST -> velocityBurst();
            case KNOWN_MULE -> knownMule();
        };
    }

    /** Salary in now and then; bills, groceries and family transfers to known payees; rarely a new small payee. */
    List<Observation> normal() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int i = 1 + r.nextInt(config.normalAccounts());
        String account = Accounts.customer(i);
        if (r.nextInt(50) == 0) {
            credit(account, Accounts.EMPLOYER, money(4500, 6500));
        }
        boolean newPayee = r.nextInt(20) == 0;
        String payee = newPayee ? Accounts.fresh("1402") : Accounts.payee(i, r.nextInt(Accounts.PAYEES_PER_ACCOUNT));
        String amount = r.nextInt(20) == 0 ? money(1000, 2500) : money(20, 600);
        Observation o = screen(Scenario.NORMAL, account, payee, amount, Accounts.device(account));
        complete(o, true);
        return List.of(o);
    }

    /** Password reset, new device, then a large transfer to a new payee. */
    List<Observation> takeover() {
        String victim = Accounts.TAKEOVER_VICTIM;
        security(victim, "PASSWORD_RESET", null, Duration.ofMinutes(15));
        String device = "dev-" + UUID.randomUUID().toString().substring(0, 6);
        security(victim, "DEVICE_CHANGED", device, Duration.ofMinutes(12));
        return List.of(screen(Scenario.ACCOUNT_TAKEOVER, victim, Accounts.fresh("1402"), money(4500, 6000), device));
    }

    /** 4 to 6 victims credit one account, which sends 85-95% onward in two transfers. */
    List<Observation> muleRing() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String mule = Accounts.fresh("1402");
        int victims = 4 + r.nextInt(3);
        BigDecimal total = BigDecimal.ZERO;
        for (int v = 0; v < victims; v++) {
            String amount = money(2500, 4000); // total >= 10,000, so each onward half is >= RM 4,250 (R2)
            credit(mule, Accounts.fresh("2201"), amount);
            total = total.add(new BigDecimal(amount));
        }
        BigDecimal onward = total.multiply(BigDecimal.valueOf(0.85 + r.nextDouble() * 0.10));
        BigDecimal first = onward.multiply(BigDecimal.valueOf(0.5)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal second = onward.subtract(first).setScale(2, RoundingMode.HALF_UP);
        List<Observation> out = new ArrayList<>();
        Observation o1 = screen(Scenario.MULE_RING, mule, Accounts.fresh("1402"), first.toPlainString(), "dev-mule");
        complete(o1, false);
        out.add(o1);
        out.add(screen(Scenario.MULE_RING, mule, Accounts.fresh("1402"), second.toPlainString(), "dev-mule"));
        return out;
    }

    /** Eight small transfers in a few minutes (compressed to now). Only the sixth onward can fire R1. */
    List<Observation> velocityBurst() {
        String account = Accounts.fresh("1402");
        List<Observation> out = new ArrayList<>();
        for (int k = 0; k < 8; k++) {
            out.add(screen(Scenario.VELOCITY_BURST, account, Accounts.fresh("1402"), money(10, 150), "dev-burst"));
        }
        return out;
    }

    /** A payee that has been reported as a mule account. */
    List<Observation> knownMule() {
        int i = 1 + ThreadLocalRandom.current().nextInt(config.normalAccounts());
        String account = Accounts.customer(i);
        return List.of(screen(Scenario.KNOWN_MULE, account, Accounts.KNOWN_MULE, money(200, 2000),
                Accounts.device(account)));
    }

    public void ensureKnownMuleListed() {
        var r = admin.send("PUT", "/watchlist/" + Accounts.KNOWN_MULE,
                Map.of("source", "POLICE_REPORT", "reason", "Simulator: reported mule account"));
        if (r.status() >= 300) {
            throw new IllegalStateException("Could not add the known mule to the watchlist: " + r.status() + " " + r.body());
        }
    }

    private Observation screen(Scenario scenario, String source, String payee, String amount, String deviceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transactionId", "SIM-" + UUID.randomUUID());
        body.put("sourceAccount", source);
        body.put("payeeAccount", payee);
        body.put("amount", amount);
        body.put("currency", "MYR");
        body.put("channel", "MOBILE");
        body.put("deviceId", deviceId);
        body.put("requestedAt", nowLocal());
        long start = System.nanoTime();
        var r = api.send("POST", "/screenings", body);
        long ms = (System.nanoTime() - start) / 1_000_000;
        if (r.status() != 201 && r.status() != 200) {
            return new Observation(scenario, scenario.fraud(), null, "ERROR", 0, List.of(), ms, r.status());
        }
        List<String> reasons = java.util.stream.StreamSupport.stream(r.body().get("reasons").spliterator(), false).map(n -> n.get("code").asText()).toList();
        return new Observation(scenario, scenario.fraud(), r.body().get("screeningId").asText(),
                r.body().get("decision").asText(), r.body().get("score").asInt(), reasons, ms, r.status());
    }

    /** The bank completes allowed transfers (and, for mules, challenged ones the mule passes). */
    private void complete(Observation o, boolean onlyIfAllowed) {
        String id = o.screeningId();
        if (id == null || (onlyIfAllowed && !"ALLOW".equals(o.decision())) || "HOLD".equals(o.decision())) {
            return;
        }
        api.send("POST", "/screenings/" + id + "/outcome", Map.of("outcome", "COMPLETED", "occurredAt", nowLocal()));
    }

    private void credit(String account, String sender, String amount) {
        api.send("POST", "/events/inbound-credits", Map.of("creditId", "CR-" + UUID.randomUUID(), "account", account,
                "senderAccount", sender, "amount", amount, "currency", "MYR", "receivedAt", nowLocal()));
    }

    private void security(String account, String type, String deviceId, Duration ago) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", "EV-" + UUID.randomUUID());
        body.put("account", account);
        body.put("type", type);
        if (deviceId != null) {
            body.put("deviceId", deviceId);
        }
        body.put("occurredAt", OffsetDateTime.now(ZoneOffset.ofHours(8)).minus(ago).toString());
        api.send("POST", "/events/security", body);
    }

    private static String nowLocal() {
        return OffsetDateTime.now(ZoneOffset.ofHours(8)).toString();
    }

    private static String money(int min, int max) {
        Random r = ThreadLocalRandom.current();
        return BigDecimal.valueOf(min + r.nextDouble() * (max - min)).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
