package dev.paykit.webhook;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;

/** Formats Stripe minor-unit amounts, respecting zero-decimal currencies (JPY, KRW...). */
public final class Money {

    private Money() {
    }

    public static String format(long minor, String currency) {
        Currency c = Currency.getInstance(currency.toUpperCase(Locale.ROOT));
        int digits = Math.max(c.getDefaultFractionDigits(), 0);
        BigDecimal amount = BigDecimal.valueOf(minor, digits);
        String symbol = switch (c.getCurrencyCode()) {
            case "USD" -> "$";
            case "EUR" -> "€";
            case "GBP" -> "£";
            default -> c.getCurrencyCode() + " ";
        };
        return (amount.signum() < 0 ? "-" : "") + symbol + amount.abs().toPlainString();
    }
}
