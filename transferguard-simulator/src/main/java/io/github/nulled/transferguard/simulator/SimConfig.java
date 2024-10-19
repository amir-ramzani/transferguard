package io.github.nulled.transferguard.simulator;

/** All settings come from the environment, so the same image runs in Compose, kind or ECS. */
public record SimConfig(String apiUrl, String tokenUrl, String clientId, String clientSecret, String adminClientId,
                        String adminClientSecret, String dbUrl, String dbUser, String dbPassword, String redisUrl,
                        int normalAccounts, long seed) {

    public static SimConfig fromEnv() {
        return new SimConfig(
                env("TG_API_URL", "http://localhost:8080/api/v1"),
                env("TG_TOKEN_URL", "http://localhost:8090/transferguard/token"),
                env("TG_CLIENT_ID", "transferguard-simulator"),
                env("TG_CLIENT_SECRET", "local-secret"),
                env("TG_ADMIN_CLIENT_ID", "local-admin"),
                env("TG_ADMIN_CLIENT_SECRET", "local-secret"),
                env("DB_URL", "jdbc:postgresql://localhost:5432/transferguard"),
                env("DB_APP_USER", "transferguard_app"),
                env("DB_APP_PASSWORD", ""),
                env("REDIS_URL", "redis://localhost:6379"),
                Integer.parseInt(env("SIM_NORMAL_ACCOUNTS", "1000")),
                Long.parseLong(env("SIM_RANDOM_SEED", "42")));
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
