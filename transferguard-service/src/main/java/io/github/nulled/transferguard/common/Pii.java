package io.github.nulled.transferguard.common;

import java.util.regex.Pattern;

/** Masks personal data (PDPA): account numbers keep only their first and last groups. */
public final class Pii {

    private static final Pattern ACCOUNT = Pattern.compile("\\b(\\d{4})-(\\d{4})-(\\d{4,8})\\b");

    private Pii() {}

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return ACCOUNT.matcher(text).replaceAll("$1-****-$3");
    }
}
