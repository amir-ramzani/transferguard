package io.github.nulled.transferguard.screening;

import io.github.nulled.transferguard.common.CanonicalJson;
import io.github.nulled.transferguard.common.Sha256;
import io.github.nulled.transferguard.screening.api.ScreeningRequest;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * SHA-256 of the canonical (RFC 8785) request body. {@code requestedAt} is normalised to an instant,
 * so the same moment written with a different offset is still the same request.
 */
@Component
public class RequestHasher {

    private final ObjectMapper mapper;

    public RequestHasher(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String hash(ScreeningRequest request) {
        ObjectNode node = mapper.valueToTree(request);
        node.put("requestedAt", request.requestedAt().toInstant().toString());
        return Sha256.hex(CanonicalJson.write(node));
    }
}
