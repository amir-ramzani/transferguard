package io.github.nulled.transferguard.simulator;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Deterministic demo accounts, so the seed, the scenarios and the k6 script agree without sharing state.
 * Normal customer i: 1402-1000-{i}; its payee j: 1402-5{j}-{i}; its device: dev-{i}.
 */
public final class Accounts {

    public static final int PAYEES_PER_ACCOUNT = 4;
    public static final String TAKEOVER_VICTIM = "1402-2000-000001";
    public static final String KNOWN_MULE = "1402-9000-000001";
    public static final String EMPLOYER = "8801-0000-000001";

    private Accounts() {}

    public static String customer(int i) {
        return "1402-1000-%06d".formatted(i);
    }

    public static String payee(int customer, int j) {
        return "1402-5%03d-%06d".formatted(j, customer);
    }

    public static String device(String account) {
        return "dev-" + account.substring(account.length() - 6);
    }

    /** A never-seen account, for new payees, mules and burst senders. */
    public static String fresh(String prefix) {
        return prefix + "-%04d-%06d".formatted(ThreadLocalRandom.current().nextInt(10_000),
                ThreadLocalRandom.current().nextInt(1_000_000));
    }
}
