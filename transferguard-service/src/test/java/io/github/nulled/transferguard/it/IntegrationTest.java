package io.github.nulled.transferguard.it;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Full-stack tests against real PostgreSQL 16 and Redis 7 (Testcontainers, one container set per
 * JVM). Skipped when Docker is not available. Tokens are accepted by a test JwtDecoder that maps the
 * token value to scopes; JWT validation itself is covered by JwtValidatorsTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"transferguard.rate-limit.enabled=false", "management.server.port=0"})
@Testcontainers(disabledWithoutDocker = true)
@Import(IntegrationTest.TestBeans.class)
public abstract class IntegrationTest {

    public static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-11-19T04:00:00Z"));
    public static final String ALL = "all";

    static PostgreSQLContainer postgres;
    static GenericContainer<?> redis;

    static synchronized void startContainers() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16")).withInitScript("db/test-roles.sql");
            redis = new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);
            postgres.start();
            redis.start();
        }
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        startContainers();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "transferguard_app");
        registry.add("spring.datasource.password", () -> "app");
        registry.add("spring.flyway.user", () -> "transferguard_migrator");
        registry.add("spring.flyway.password", () -> "migrator");
        // A placeholder rather than the URL itself, so a subclass can point at a dead Redis via it.redis-url.
        registry.add("transferguard.redis.url", () -> "${it.redis-url:" + redisUrl() + "}");
    }

    public static String redisUrl() {
        return "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        java.time.Clock testClock() {
            return CLOCK;
        }

        /** "all" carries every scope; "scopes~a+b" carries the named ones; "none" carries none. */
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return token -> {
                String scopes = switch (token) {
                    case ALL -> String.join(" ", List.of("screening.write", "screening.read", "events.write",
                            "cases.read", "cases.write", "rules.admin", "audit.read").stream()
                            .map(s -> "transferguard/" + s).toList());
                    case "none" -> "";
                    default -> String.join(" ", List.of(token.replace("scopes~", "").split("\\+")).stream()
                            .map(s -> "transferguard/" + s).toList());
                };
                Instant now = Instant.now();
                return Jwt.withTokenValue(token).header("alg", "none").subject("analyst-it")
                        .claim("client_id", "it-client").claim("scope", scopes)
                        .issuedAt(now).expiresAt(now.plusSeconds(600)).build();
            };
        }
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JsonMapper mapper;

    @Autowired
    protected JdbcTemplate appJdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    public record Response(int status, Map<String, List<String>> headers, JsonNode body) {
        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .findFirst().map(e -> e.getValue().get(0)).orElse(null);
        }
    }

    protected Response call(String method, String path, Object body) {
        return call(method, path, body, ALL, Map.of());
    }

    protected Response call(String method, String path, Object body, String token, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json");
            headers.forEach(b::header);
            String json = body == null ? null : body instanceof String s ? s : mapper.writeValueAsString(body);
            b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = r.body() == null || r.body().isBlank() ? null : mapper.readTree(r.body());
            return new Response(r.statusCode(), r.headers().map(), node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Superuser access, for setting up history and for tampering with the audit log. */
    protected static JdbcTemplate superuser() {
        return new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()));
    }

    protected static String account() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "%04d-%04d-%06d".formatted(r.nextInt(1000, 9999), r.nextInt(10000), r.nextInt(1_000_000));
    }

    protected static String txn() {
        return "IT-" + java.util.UUID.randomUUID();
    }

    protected static Map<String, Object> transfer(String txn, String source, String payee, String amount) {
        return transfer(txn, source, payee, amount, null);
    }

    protected static Map<String, Object> transfer(String txn, String source, String payee, String amount,
                                                  String deviceId) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("transactionId", txn);
        m.put("sourceAccount", source);
        m.put("payeeAccount", payee);
        m.put("amount", amount);
        m.put("currency", "MYR");
        m.put("channel", "MOBILE");
        if (deviceId != null) {
            m.put("deviceId", deviceId);
        }
        m.put("requestedAt", CLOCK.local());
        return m;
    }

    protected static List<String> reasonCodes(Response r) {
        return r.body().get("reasons").valueStream().map(n -> n.get("code").asString()).toList();
    }

    protected static OffsetDateTime localNow() {
        return OffsetDateTime.parse(CLOCK.local());
    }
}
