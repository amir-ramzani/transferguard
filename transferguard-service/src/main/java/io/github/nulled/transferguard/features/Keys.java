package io.github.nulled.transferguard.features;

/** Redis key layout. Per-account keys share the {account} hash tag so they land in one cluster slot. */
public final class Keys {

    public static final String WATCHLIST = "tg:watchlist";
    public static final String RULES_CHANGED = "tg:rules:changed";

    private Keys() {}

    public static String out(String acct) { return "tg:{" + acct + "}:out"; }
    public static String outDone(String acct) { return "tg:{" + acct + "}:outdone"; }
    public static String in(String acct) { return "tg:{" + acct + "}:in"; }
    public static String payees(String acct) { return "tg:{" + acct + "}:payees"; }
    public static String security(String acct) { return "tg:{" + acct + "}:sec"; }
    public static String average(String acct) { return "tg:{" + acct + "}:avg"; }
    public static String idempotency(String txnId) { return "tg:idem:" + txnId; }
}
