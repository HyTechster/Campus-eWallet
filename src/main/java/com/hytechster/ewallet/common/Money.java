package com.hytechster.ewallet.common;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Amounts are {@code long} sen everywhere (RM 12.50 = 1250). This class is the only place
 * that converts between sen and the text a person types or reads.
 */
public final class Money {

    /** What a person may type in an amount field: whole ringgit with up to 2 decimals. */
    public static final String INPUT_PATTERN = "(?i)^\\s*(RM\\s*)?\\d{1,7}(\\.\\d{1,2})?\\s*$";

    private static final Pattern INPUT = Pattern.compile(INPUT_PATTERN, Pattern.CASE_INSENSITIVE);

    private Money() {
    }

    /**
     * Parses "12.5", "12.50" or "RM 12.50" into sen. No floating point involved.
     */
    public static long parseSen(String input) {
        if (input == null || !INPUT.matcher(input).matches()) {
            throw new BusinessException("Enter an amount like 12.50");
        }
        String digits = input.trim().replaceFirst("(?i)^RM\\s*", "");
        return new BigDecimal(digits).movePointRight(2).longValueExact();
    }

    /** 1250 becomes "12.50". Used to refill form fields. */
    public static String plain(long sen) {
        return BigDecimal.valueOf(sen, 2).toPlainString();
    }

    /** 1250 becomes "RM 12.50", -1250 becomes "-RM 12.50". */
    public static String format(long sen) {
        String abs = String.format("%,.2f", BigDecimal.valueOf(Math.abs(sen), 2));
        return (sen < 0 ? "-RM " : "RM ") + abs;
    }
}
