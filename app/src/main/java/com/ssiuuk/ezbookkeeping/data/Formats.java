package com.ssiuuk.ezbookkeeping.data;

import android.content.Context;
import android.graphics.Color;
import android.text.format.DateFormat;
import android.text.format.DateUtils;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Calendar;
import java.util.Currency;
import java.util.Date;
import java.util.Locale;

/** Formats amounts, dates and colors the way the native screens show them. */
public final class Formats {
    private Formats() {
    }

    /** Amounts from the server are whole cents, whatever the currency. */
    public static String amount(long cents, String currencyCode) {
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.getDefault());

        try {
            Currency currency = Currency.getInstance(currencyCode);
            format.setCurrency(currency);
        } catch (IllegalArgumentException | NullPointerException ex) {
            format = NumberFormat.getNumberInstance(Locale.getDefault());
        }

        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(BigDecimal.valueOf(cents).movePointLeft(2));
    }

    public static String signedAmount(long cents, String currencyCode, boolean negative) {
        String text = amount(Math.abs(cents), currencyCode);
        return (negative ? "−" : "+") + text;
    }

    /** Parses what the user typed in an amount field into cents, or returns null. */
    public static Long parseAmount(String text) {
        if (text == null) {
            return null;
        }

        String cleaned = text.trim().replace(" ", "").replace(" ", "");

        if (cleaned.isEmpty()) {
            return null;
        }

        int lastComma = cleaned.lastIndexOf(',');
        int lastDot = cleaned.lastIndexOf('.');

        if (lastComma > lastDot) {
            // "1.234,56" or "12,5": the comma is the decimal separator
            cleaned = cleaned.replace(".", "").replace(',', '.');
        } else {
            cleaned = cleaned.replace(",", "");
        }

        try {
            return new BigDecimal(cleaned).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            return null;
        }
    }

    /** Shows cents in an amount field without grouping, for example 1234.5 → "1234.50". */
    public static String editableAmount(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2).toPlainString();
    }

    public static String dayHeader(Context context, long unixSeconds) {
        long millis = unixSeconds * 1000;

        if (DateUtils.isToday(millis)) {
            return context.getString(com.ssiuuk.ezbookkeeping.R.string.today);
        }

        if (DateUtils.isToday(millis + DateUtils.DAY_IN_MILLIS)) {
            return context.getString(com.ssiuuk.ezbookkeeping.R.string.yesterday);
        }

        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(millis);
        String skeleton = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) ? "EEEEMMMMd" : "EEEEMMMMdyyyy";
        return DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton), then).toString();
    }

    public static String date(Context context, long millis) {
        return DateFormat.getMediumDateFormat(context).format(new Date(millis));
    }

    public static String time(Context context, long millis) {
        return DateFormat.getTimeFormat(context).format(new Date(millis));
    }

    public static long startOfDay(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    /** Parses a color the server stores as six hex digits, with a fallback. */
    public static int color(String hex, int fallback) {
        if (hex == null || hex.length() != 6) {
            return fallback;
        }

        try {
            return Color.parseColor("#" + hex);
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    public static String initial(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "?";
        }

        int codePoint = name.trim().codePointAt(0);
        return new String(Character.toChars(codePoint)).toUpperCase(Locale.getDefault());
    }
}
