package com.ssiuuk.ezbookkeeping.data;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Works out an amount typed as a sum, such as "12.50+3.20×2" or "(18-2.5)/3": + − × ÷ (or - * /) and brackets,
 * multiplication and division before addition and subtraction. Each number may use a comma or a dot for decimals,
 * as {@link Formats#parseAmount} reads them.
 */
public final class AmountExpression {
    private static final MathContext PRECISION = MathContext.DECIMAL64;

    private final String text;
    private int position;

    private AmountExpression(String text) {
        this.text = text;
    }

    /** The value, or null when the text isn't a sum that can be worked out (such as "12+" or a division by zero). */
    public static BigDecimal evaluate(String input) {
        if (input == null) {
            return null;
        }

        String cleaned = input.replace(" ", "").replace(" ", "").replace(" ", "")
            .replace('×', '*').replace('÷', '/').replace('−', '-').replace('–', '-');

        if (cleaned.isEmpty()) {
            return null;
        }

        AmountExpression expression = new AmountExpression(cleaned);

        try {
            BigDecimal value = expression.sum();
            return expression.position == cleaned.length() ? value : null;
        } catch (ArithmeticException | NumberFormatException ex) {
            return null;
        }
    }

    /** Whether the text is a sum rather than a plain number, so the result is worth showing. */
    public static boolean isSum(String input) {
        if (input == null) {
            return false;
        }

        String trimmed = input.trim();

        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);

            // a leading minus is just a negative number
            if ("+*/×÷()".indexOf(c) >= 0 || (i > 0 && "-−–".indexOf(c) >= 0)) {
                return true;
            }
        }

        return false;
    }

    private BigDecimal sum() {
        BigDecimal value = product();

        while (position < text.length()) {
            char c = text.charAt(position);

            if (c == '+') {
                position++;
                value = value.add(product());
            } else if (c == '-') {
                position++;
                value = value.subtract(product());
            } else {
                break;
            }
        }

        return value;
    }

    private BigDecimal product() {
        BigDecimal value = factor();

        while (position < text.length()) {
            char c = text.charAt(position);

            if (c == '*') {
                position++;
                value = value.multiply(factor());
            } else if (c == '/') {
                position++;
                BigDecimal divisor = factor();

                if (divisor.signum() == 0) {
                    throw new ArithmeticException("division by zero");
                }

                value = value.divide(divisor, PRECISION);
            } else {
                break;
            }
        }

        return value;
    }

    private BigDecimal factor() {
        if (position >= text.length()) {
            throw new NumberFormatException("missing number");
        }

        char c = text.charAt(position);

        if (c == '-' || c == '+') {
            position++;
            BigDecimal value = factor();
            return c == '-' ? value.negate() : value;
        }

        if (c == '(') {
            position++;
            BigDecimal value = sum();

            if (position >= text.length() || text.charAt(position) != ')') {
                throw new NumberFormatException("missing )");
            }

            position++;
            return value;
        }

        int start = position;

        while (position < text.length() && "0123456789.,".indexOf(text.charAt(position)) >= 0) {
            position++;
        }

        if (start == position) {
            throw new NumberFormatException("unexpected " + c);
        }

        return number(text.substring(start, position));
    }

    /** One number, with a comma or a dot for decimals: "1.234,56", "1,234.56", "12,5" and "12.5" all work. */
    private static BigDecimal number(String token) {
        int lastComma = token.lastIndexOf(',');
        int lastDot = token.lastIndexOf('.');
        String cleaned = lastComma > lastDot ? token.replace(".", "").replace(',', '.') : token.replace(",", "");
        return new BigDecimal(cleaned);
    }

    /** Rounds a result to cents, the way amounts are kept. */
    public static long toCents(BigDecimal value) {
        return value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
