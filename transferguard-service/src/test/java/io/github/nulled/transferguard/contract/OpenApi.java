package io.github.nulled.transferguard.contract;

import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

public final class OpenApi {

    public static final Path SPEC = Path.of("../docs/openapi.yaml");

    private OpenApi() {}

    public static JsonNode load() {
        return new YAMLMapper().readTree(SPEC.toFile());
    }
}
