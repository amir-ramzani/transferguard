package io.github.nulled.transferguard.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

class CanonicalJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    /** The example from RFC 8785, section 3.2.3 / appendix. */
    @Test
    void rfc8785Example() {
        String input = """
                {
                  "numbers": [333333333.33333329, 1E30, 4.50, 2e-3, 0.000000000000000000000000001],
                  "string": "\\u20ac$\\u000F\\u000aA'\\u0042\\u0022\\u005c\\\\\\"\\/",
                  "literals": [null, true, false]
                }""";
        assertThat(CanonicalJson.write(tree(input))).isEqualTo(
                "{\"literals\":[null,true,false],\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27],"
                        + "\"string\":\"€$\\u000f\\nA'B\\\"\\\\\\\\\\\"/\"}");
    }

    @ParameterizedTest
    @CsvSource({"0,0", "-0,0", "1,1", "100,100", "0.8,0.8", "1.0,1", "1e21,1e+21", "1e20,100000000000000000000",
            "0.000001,0.000001", "0.0000001,1e-7", "-1.5,-1.5", "123456789012,123456789012"})
    void numbersUseEcmaScriptFormatting(double value, String expected) {
        assertThat(CanonicalJson.number(value)).isEqualTo(expected);
    }

    @Test
    void keyOrderDoesNotMatter() {
        assertThat(CanonicalJson.write(tree("{\"b\":1,\"a\":{\"d\":2,\"c\":3}}")))
                .isEqualTo(CanonicalJson.write(tree("{\"a\":{\"c\":3,\"d\":2},\"b\":1}")))
                .isEqualTo("{\"a\":{\"c\":3,\"d\":2},\"b\":1}");
    }

    private JsonNode tree(String json) {
        try {
            return mapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
