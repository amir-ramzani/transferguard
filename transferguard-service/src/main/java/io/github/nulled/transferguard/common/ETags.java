package io.github.nulled.transferguard.common;

/** Versions travel as strong ETags ({@code "3"}); If-Match also accepts weak or unquoted forms. */
public final class ETags {

    private ETags() {}

    public static String of(int version) {
        return "\"" + version + "\"";
    }

    public static int require(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ApiException.preconditionRequired();
        }
        String v = ifMatch.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        v = v.replace("\"", "");
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw ApiException.preconditionFailed("If-Match does not name a known version.");
        }
    }
}
