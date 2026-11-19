package io.github.nulled.transferguard.simulator;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Minimal TransferGuard client with OAuth2 client-credentials token caching. */
public final class ApiClient {

    public record Response(int status, JsonNode body, Map<String, java.util.List<String>> headers) {
        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).findFirst()
                    .map(e -> e.getValue().get(0)).orElse(null);
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final String apiUrl;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private volatile String token;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    public ApiClient(String apiUrl, String tokenUrl, String clientId, String clientSecret) {
        this.apiUrl = apiUrl;
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public Response send(String method, String path, Object body) {
        return send(method, path, body, Map.of());
    }

    public Response send(String method, String path, Object body, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiUrl + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token())
                    .header("Content-Type", "application/json");
            headers.forEach(b::header);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = r.body() == null || r.body().isBlank() ? null : mapper.readTree(r.body());
            return new Response(r.statusCode(), node, r.headers().map());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(method + " " + path + " failed: " + e.getMessage(), e);
        }
    }

    private synchronized String token() throws Exception {
        if (token != null && Instant.now().isBefore(tokenExpiry.minusSeconds(60))) {
            return token;
        }
        String form = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IllegalStateException("Token request failed: " + r.statusCode() + " " + r.body());
        }
        JsonNode json = mapper.readTree(r.body());
        token = json.get("access_token").asString();
        tokenExpiry = Instant.now().plusSeconds(json.path("expires_in").asLong(300));
        return token;
    }
}
