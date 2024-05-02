package io.github.nulled.transferguard.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Money is always {@link BigDecimal} with scale 2 and travels as a string such as {@code "4800.00"}. */
public final class Money {

    public static final String PATTERN = "^(0|[1-9][0-9]{0,12})\\.[0-9]{2}$";

    private Money() {}

    public static BigDecimal parse(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
    }

    public static String toWire(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Human format for reason messages: {@code RM 4,800} or {@code RM 4,800.50}. */
    public static String display(BigDecimal value) {
        BigDecimal v = value.setScale(2, RoundingMode.HALF_UP);
        boolean whole = v.remainder(BigDecimal.ONE).signum() == 0;
        DecimalFormat format = new DecimalFormat(whole ? "#,##0" : "#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return "RM " + format.format(v);
    }
}
