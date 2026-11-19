package io.github.nulled.transferguard.common;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * RFC 8785 JSON Canonicalization Scheme (JCS): object members sorted by UTF-16 code units, no
 * whitespace, ECMAScript number formatting and minimal string escaping. Key order and number
 * formatting therefore cannot change a hash.
 */
public final class CanonicalJson {

    private CanonicalJson() {}

    public static String write(JsonNode node) {
        StringBuilder out = new StringBuilder(256);
        write(node, out);
        return out.toString();
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isObject()) {
            List<Map.Entry<String, JsonNode>> members = new ArrayList<>(node.properties());
            // String.compareTo compares UTF-16 code units, exactly what RFC 8785 requires.
            members.sort(Map.Entry.comparingByKey());
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> member : members) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(member.getKey(), out);
                out.append(':');
                write(member.getValue(), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(node.get(i), out);
            }
            out.append(']');
        } else if (node.isString()) {
            writeString(node.asString(), out);
        } else if (node.isBoolean()) {
            out.append(node.booleanValue() ? "true" : "false");
        } else if (node.isNumber()) {
            out.append(number(node.doubleValue()));
        } else {
            writeString(node.asString(), out);
        }
    }

    /** ECMAScript Number.prototype.toString for finite doubles, as RFC 8785 section 3.2.2.3 requires. */
    static String number(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("JSON numbers must be finite");
        }
        if (value == 0) {
            return "0";
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e21) {
            return new BigDecimal(value).toPlainString();
        }
        // Shortest round-trip digits, then ECMAScript layout rules.
        BigDecimal shortest = new BigDecimal(Double.toString(Math.abs(value))).stripTrailingZeros();
        String digits = shortest.unscaledValue().toString();
        int k = digits.length();
        int n = k - shortest.scale(); // decimal exponent: value = 0.digits * 10^n
        StringBuilder sb = new StringBuilder();
        if (value < 0) {
            sb.append('-');
        }
        if (k <= n && n <= 21) {
            sb.append(digits).append("0".repeat(n - k));
        } else if (0 < n && n <= 21) {
            sb.append(digits, 0, n).append('.').append(digits, n, k);
        } else if (-6 < n && n <= 0) {
            sb.append("0.").append("0".repeat(-n)).append(digits);
        } else {
            int e = n - 1;
            sb.append(digits.charAt(0));
            if (k > 1) {
                sb.append('.').append(digits, 1, k);
            }
            sb.append('e').append(e >= 0 ? "+" : "-").append(Math.abs(e));
        }
        return sb.toString();
    }

    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
