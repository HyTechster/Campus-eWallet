package com.hytechster.ewallet.common;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * App settings from {@code ewallet.*} in application.yml. Limits are in sen.
 */
@ConfigurationProperties("ewallet")
public record EwalletProperties(ZoneId displayZone, Limits limits) {

    public record Limits(long topUpMax, long transferMax, long paymentMax) {
    }
}
