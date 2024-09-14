package io.github.nulled.transferguard.contract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

public final class OpenApi {

    public static final Path SPEC = Path.of("../docs/openapi.yaml");

    private OpenApi() {}

    public static JsonNode load() {
        try {
            return new YAMLMapper().readTree(SPEC.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
