package com.hytechster.ewallet.common;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * Formatting helpers for templates, used as {@code ${@fmt.money(balance)}}.
 * Times are stored in UTC and shown in the display zone (Asia/Kuala_Lumpur).
 */
@Component("fmt")
public class ViewFormat {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final EwalletProperties properties;

    public ViewFormat(EwalletProperties properties) {
        this.properties = properties;
    }

    public String money(long sen) {
        return Money.format(sen);
    }

    /** Unsigned amount, for use next to an explicit sign or label. */
    public String abs(long sen) {
        return Money.format(Math.abs(sen));
    }

    /** "+ RM 10.00" or "− RM 10.00" (true minus sign). */
    public String signed(long sen) {
        return (sen < 0 ? "− " : "+ ") + Money.format(Math.abs(sen));
    }

    /** 123450 becomes "1,234.50", without the RM. For the big balance figure. */
    public String number(long sen) {
        return String.format("%,.2f", java.math.BigDecimal.valueOf(sen, 2));
    }

    public String plain(long sen) {
        return Money.plain(sen);
    }

    public String dateTime(Instant instant) {
        return local(instant).format(DATE_TIME);
    }

    public String date(Instant instant) {
        return local(instant).format(DATE);
    }

    public String time(Instant instant) {
        return local(instant).format(TIME);
    }

    public String isoDate(Instant instant) {
        return local(instant).format(ISO_DATE);
    }

    private ZonedDateTime local(Instant instant) {
        return instant.atZone(properties.displayZone());
    }
}
