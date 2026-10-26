package com.ssiuuk.ezbookkeeping.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Sums typed in the amount fields. */
@RunWith(AndroidJUnit4.class)
public class AmountExpressionTest {
    @Test
    public void plainAmountsReadAsBefore() {
        assertEquals(Long.valueOf(1250), Formats.parseAmount("12.50"));
        assertEquals(Long.valueOf(1250), Formats.parseAmount("12,5"));
        assertEquals(Long.valueOf(123456), Formats.parseAmount("1,234.56"));
        assertEquals(Long.valueOf(123456), Formats.parseAmount("1.234,56"));
        assertEquals(Long.valueOf(-500), Formats.parseAmount("-5"));
        assertNull(Formats.parseAmount(""));
        assertNull(Formats.parseAmount("abc"));
        assertFalse(AmountExpression.isSum("12.50"));
        assertFalse(AmountExpression.isSum("-5"));
    }

    @Test
    public void sumsAreWorkedOut() {
        assertEquals(Long.valueOf(1570), Formats.parseAmount("12.50+3.20"));
        assertEquals(Long.valueOf(1890), Formats.parseAmount("12.50+3.20×2"));
        assertEquals(Long.valueOf(3140), Formats.parseAmount("(12.50+3.20)*2"));
        assertEquals(Long.valueOf(517), Formats.parseAmount("(18−2,5)÷3"));
        assertEquals(Long.valueOf(333), Formats.parseAmount("10/3"));
        assertEquals(Long.valueOf(750), Formats.parseAmount(" 10 - 2.5 "));
        assertEquals(Long.valueOf(-250), Formats.parseAmount("5-7.5"));
        assertTrue(AmountExpression.isSum("12.50+3.20"));
        assertTrue(AmountExpression.isSum("10-2"));
    }

    @Test
    public void unfinishedSumsAreNotAmounts() {
        assertNull(Formats.parseAmount("12+"));
        assertNull(Formats.parseAmount("×3"));
        assertNull(Formats.parseAmount("(12+3"));
        assertNull(Formats.parseAmount("12)"));
        assertNull(Formats.parseAmount("10÷0"));
        assertNull(Formats.parseAmount("1.2.3+4"));
    }
}
