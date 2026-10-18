package com.ssiuuk.ezbookkeeping.data;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a purchase out of a payment notification, such as Google Wallet's
 * "Corner Café" / "HK$12.50 with Visa •••• 1234" after paying with the phone.
 */
public final class PaymentNotification {
    /** Google Wallet, and Google Pay where it still shows tap-to-pay purchases. */
    public static final String[] WALLET_PACKAGES = {
        "com.google.android.apps.walletnfcrel",
        "com.google.android.apps.nbu.paisa.user",
    };

    // a currency in front of the number ("HK$12.50", "USD 12.50", "€12,50") or after it ("12,50 €", "12.50 HKD")
    private static final String SYMBOL = "(?:[A-Z]{3}|[A-Z]{0,3}\\$|[€£¥₩₹₱฿₫₪₺₽]|R\\$|zł|kr|Fr\\.?)";
    private static final String NUMBER = "\\d{1,3}(?:[,. \\u00a0\\u202f]\\d{3})*(?:[.,]\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?";
    private static final Pattern AMOUNT_BEFORE = Pattern.compile("(-?)(?<![A-Za-z])(" + SYMBOL + ")\\s?(" + NUMBER + ")(?![\\d.,]*\\d)");
    private static final Pattern AMOUNT_AFTER = Pattern.compile("(-?)(?<![\\d.,])(" + NUMBER + ")\\s?(" + SYMBOL + ")(?![A-Za-z])");
    private static final Pattern CARD_DIGITS = Pattern.compile("[•·*●∙.xX]{2,}\\s*(\\d{4})\\b|\\b(?:ending(?: in)?|end(?:ing)?)\\s+(\\d{4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WITH_CARD = Pattern.compile("\\b(?:with|using|on|via)\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern AT_MERCHANT = Pattern.compile("\\bat\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    public long amount;
    /** ISO code when the notification makes it clear, otherwise empty and the account's currency is used. */
    public String currency = "";
    public String merchant = "";
    /** How the card was named, like "Visa •••• 1234". */
    public String card = "";
    /** The card's last four digits, when shown. */
    public String cardDigits = "";
    public boolean refund;

    /** Returns the purchase in a notification, or null when it doesn't look like one. */
    public static PaymentNotification parse(String title, String text) {
        title = clean(title);
        text = clean(text);

        if (title.isEmpty() && text.isEmpty()) {
            return null;
        }

        PaymentNotification payment = new PaymentNotification();
        String amountLine;
        Matcher matcher;

        if ((matcher = findAmount(text)) != null) {
            amountLine = text;
        } else if ((matcher = findAmount(title)) != null) {
            amountLine = title;
        } else {
            return null;
        }

        String symbol = matcher.group(AMOUNT_BEFORE.equals(matcher.pattern()) ? 2 : 3);
        String number = matcher.group(AMOUNT_BEFORE.equals(matcher.pattern()) ? 3 : 2);
        Long cents = parseNumber(number);

        if (cents == null || cents <= 0) {
            return null;
        }

        payment.amount = cents;
        payment.currency = currencyCode(symbol);
        payment.refund = !matcher.group(1).isEmpty() || containsWord(title + " " + text, "refund", "refunded", "returned");

        String rest = (amountLine.substring(0, matcher.start()) + " " + amountLine.substring(matcher.end())).trim();
        Matcher withCard = WITH_CARD.matcher(rest);

        if (withCard.find()) {
            payment.card = withCard.group(1).trim();
            rest = rest.substring(0, withCard.start()).trim();
        }

        Matcher digits = CARD_DIGITS.matcher(title + " " + text);

        if (digits.find()) {
            payment.cardDigits = digits.group(1) != null ? digits.group(1) : digits.group(2);

            if (payment.card.isEmpty()) {
                payment.card = cardAround(title + " " + text, digits);
            }
        }

        Matcher at = AT_MERCHANT.matcher(rest);

        if (at.find()) {
            payment.merchant = at.group(1).trim();
        } else {
            // Wallet's usual layout: the shop is the title, the amount and card the text
            payment.merchant = amountLine.equals(text) ? title : rest;
        }

        if (payment.refund) {
            payment.merchant = payment.merchant.replaceFirst("(?i)^refund(ed)?\\s+(from\\s+)?", "");
        }

        payment.merchant = trimPunctuation(payment.merchant);

        if (payment.merchant.length() > 64) {
            payment.merchant = payment.merchant.substring(0, 64).trim();
        }

        return payment;
    }

    /** A key for remembering which account a card belongs to. */
    public String cardKey() {
        if (!cardDigits.isEmpty()) {
            return cardDigits;
        }

        return card.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static Matcher findAmount(String line) {
        if (line.isEmpty()) {
            return null;
        }

        Matcher before = AMOUNT_BEFORE.matcher(line);

        while (before.find()) {
            if (!looksLikeCardDigits(line, before)) {
                return before;
            }
        }

        Matcher after = AMOUNT_AFTER.matcher(line);

        while (after.find()) {
            if (!looksLikeCardDigits(line, after)) {
                return after;
            }
        }

        return null;
    }

    private static boolean looksLikeCardDigits(String line, Matcher matcher) {
        int start = matcher.start();
        return start > 0 && "•·*●∙".indexOf(line.charAt(start - 1)) >= 0;
    }

    private static String cardAround(String line, Matcher digits) {
        String before = line.substring(0, digits.start()).trim();
        int space = before.lastIndexOf(' ');
        String name = space >= 0 ? before.substring(space + 1) : before;
        return (name.isEmpty() ? "" : name + " ") + "•••• " + digits.group(digits.group(1) != null ? 1 : 2);
    }

    static Long parseNumber(String number) {
        String digits = number.replaceAll("[ \\u00a0\\u202f]", "");
        int lastDot = digits.lastIndexOf('.');
        int lastComma = digits.lastIndexOf(',');
        int decimal = Math.max(lastDot, lastComma);

        // "1,234" and "1.234" are thousands; "12,50" and "12.5" are decimals
        if (decimal >= 0 && digits.length() - decimal - 1 == 3) {
            decimal = -1;
        }

        String whole = decimal >= 0 ? digits.substring(0, decimal) : digits;
        String fraction = decimal >= 0 ? digits.substring(decimal + 1) : "";
        whole = whole.replaceAll("[.,]", "");

        if (whole.isEmpty() && fraction.isEmpty()) {
            return null;
        }

        try {
            long cents = (whole.isEmpty() ? 0 : Long.parseLong(whole)) * 100;

            if (!fraction.isEmpty()) {
                cents += Long.parseLong(fraction.length() == 1 ? fraction + "0" : fraction);
            }

            return cents;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static String currencyCode(String symbol) {
        switch (symbol) {
            case "€": return "EUR";
            case "£": return "GBP";
            case "₩": return "KRW";
            case "₹": return "INR";
            case "₱": return "PHP";
            case "฿": return "THB";
            case "₫": return "VND";
            case "₪": return "ILS";
            case "₺": return "TRY";
            case "₽": return "RUB";
            case "R$": return "BRL";
            case "zł": return "PLN";
            case "US$": return "USD";
            case "HK$": return "HKD";
            case "NT$": return "TWD";
            case "S$": return "SGD";
            case "A$": case "AU$": return "AUD";
            case "C$": case "CA$": return "CAD";
            case "NZ$": return "NZD";
            case "MOP$": return "MOP";
            default:
                // "$", "¥", "kr" are used by several currencies; leave those to the account
                return symbol.matches("[A-Z]{3}") ? symbol : "";
        }
    }

    private static boolean containsWord(String line, String... words) {
        String lower = " " + line.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", " ") + " ";

        for (String word : words) {
            if (lower.contains(" " + word + " ")) {
                return true;
            }
        }

        return false;
    }

    private static String trimPunctuation(String value) {
        return value.replaceAll("^[\\s:·•,.\\-–—]+|[\\s:·•,.\\-–—]+$", "");
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }
}
