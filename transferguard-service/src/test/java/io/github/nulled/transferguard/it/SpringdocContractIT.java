package io.github.nulled.transferguard.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nulled.transferguard.contract.OpenApi;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The operations springdoc finds in the running app are exactly those in docs/openapi.yaml. */
class SpringdocContractIT extends IntegrationTest {

    private static final Set<String> METHODS = Set.of("get", "put", "post", "patch", "delete");

    @Test
    void generatedOperationsMatchTheCommittedContract() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(operations(mapper.readTree(r.body()), "/api/v1")).isEqualTo(operations(OpenApi.load(), ""));
    }

    private static Set<String> operations(JsonNode spec, String stripPrefix) {
        Set<String> ops = new TreeSet<>();
        spec.get("paths").properties().forEach(p -> p.getValue().properties().forEach(op -> {
            if (METHODS.contains(op.getKey())) {
                ops.add(op.getKey().toUpperCase() + " " + p.getKey().substring(stripPrefix.length()));
            }
        }));
        return ops;
    }
}
