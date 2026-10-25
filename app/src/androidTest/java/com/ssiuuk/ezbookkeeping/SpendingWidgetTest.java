package com.ssiuuk.ezbookkeeping;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.ssiuuk.ezbookkeeping.data.Models;
import com.ssiuuk.ezbookkeeping.data.Server;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;

/** What the Spending widget keeps and shows, without a server. */
@RunWith(AndroidJUnit4.class)
public class SpendingWidgetTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test
    public void currencyWithoutRateIsLeftOutAndNamed() throws Exception {
        Server server = server("home", "demo", null);
        Models.ExchangeRates rates = Models.ExchangeRates.parse(new JSONObject()
            .put("baseCurrency", "USD")
            .put("exchangeRates", new JSONArray().put(new JSONObject().put("currency", "GBP").put("rate", "0.8"))));
        JSONObject data = SpendingWidget.combine(context, null, SpendingWidget.identity(server, null), "GBP",
            amounts("GBP", 1000, "EUR", 500), rates, null, null, 1);

        assertEquals(1000, data.getJSONObject("totals").getJSONArray("thisMonth").getLong(1));
        assertEquals("[\"EUR\"]", data.getJSONArray("unconverted").toString());
        assertTrue(SpendingWidget.heading(context, settings(""), server, data).contains("EUR"));
    }

    @Test
    public void failedRatesRequestSaysTotalsAreIncomplete() throws Exception {
        Server server = server("home", "demo", null);
        // only foreign spending: zero in the default currency, which must not pass for a complete total
        JSONObject data = SpendingWidget.combine(context, null, SpendingWidget.identity(server, null), "GBP",
            amounts("EUR", 500), null, null, null, 1);

        assertEquals(0, data.getJSONObject("totals").getJSONArray("thisMonth").getLong(1));
        assertEquals("[\"EUR\"]", data.getJSONArray("unconverted").toString());
        assertTrue(SpendingWidget.heading(context, settings(""), server, data).contains("EUR"));
    }

    @Test
    public void defaultCurrencyAloneIsComplete() throws Exception {
        Server server = server("home", "demo", null);
        JSONObject data = SpendingWidget.combine(context, null, SpendingWidget.identity(server, null), "GBP",
            amounts("GBP", 1000), null, null, null, 1);

        assertEquals(0, data.getJSONArray("unconverted").length());
        assertEquals(SpendingWidget.periodName(context, "thisMonth"), SpendingWidget.heading(context, settings(""), server, data));
    }

    @Test
    public void figuresOfAnotherBookUserOrServerAreNotShownOrReused() throws Exception {
        Server server = server("home", "demo", "family");
        JSONObject kept = new JSONObject()
            .put("currency", "GBP")
            .put("identity", SpendingWidget.identity(server, "family"))
            .put("recent", new JSONArray().put(new JSONObject().put("id", "1")));

        assertSame(kept, SpendingWidget.shownData(kept, server, "family"));
        assertNull(SpendingWidget.shownData(kept, server, null));
        assertNull(SpendingWidget.shownData(kept, server("home", "alex", "family"), "family"));
        assertNull(SpendingWidget.shownData(kept, server("work", "demo", "family"), "family"));

        // a failed transactions request in the user's own book doesn't bring back the family book's transactions
        JSONObject data = SpendingWidget.combine(context, kept, SpendingWidget.identity(server, null), "GBP",
            amounts("GBP", 1000), null, null, "Can't reach the server", 1);
        assertFalse(data.has("recent"));
        assertEquals(SpendingWidget.identity(server, null), data.getString("identity"));
    }

    @Test
    public void olderRefreshIsDropped() {
        int first = SpendingWidget.nextGeneration(-7);
        int second = SpendingWidget.nextGeneration(-7);

        assertFalse(SpendingWidget.isLatest(-7, first));
        assertTrue(SpendingWidget.isLatest(-7, second));
    }

    @Test
    public void addAndEditUseTheWidgetsBook() {
        Server inFamilyBook = server("home", "demo", "family");

        // following the app, a widget shows the app's book; otherwise its own choice, even while the app is in another book
        assertEquals("family", SpendingWidget.resolvedBook(settings(""), inFamilyBook));
        assertNull(SpendingWidget.resolvedBook(settings(SpendingWidget.BOOK_PERSONAL), inFamilyBook));
        assertEquals("trip", SpendingWidget.resolvedBook(settings("trip"), inFamilyBook));

        Intent own = TransactionEditActivity.inBook(TransactionEditActivity.addIntent(context, Models.TRANSACTION_EXPENSE), "");
        assertEquals("", own.getStringExtra("book_id"));
        Intent edit = TransactionEditActivity.inBook(TransactionEditActivity.editIntent(context, "42"), "trip");
        assertEquals("trip", edit.getStringExtra("book_id"));
        assertFalse(TransactionEditActivity.inBook(TransactionEditActivity.editIntent(context, "42"), null).hasExtra("book_id"));
    }

    private static Server server(String id, String username, String bookId) {
        Server server = new Server(id, id, "http://" + id);
        server.token = "token";
        server.userJson = "{\"username\":\"" + username + "\",\"defaultCurrency\":\"GBP\"}";
        server.bookId = bookId;
        return server;
    }

    private static SpendingWidget.Settings settings(String book) {
        SpendingWidget.Settings settings = new SpendingWidget.Settings();
        settings.book = book;
        settings.bookName = book;
        return settings;
    }

    /** This month's spending in each currency, as amounts.json gives it. */
    private static Map<String, Models.PeriodAmounts> amounts(Object... currencyAndExpense) throws Exception {
        JSONArray list = new JSONArray();

        for (int i = 0; i + 1 < currencyAndExpense.length; i += 2) {
            list.put(new JSONObject()
                .put("currency", currencyAndExpense[i])
                .put("incomeAmount", "0")
                .put("expenseAmount", String.valueOf(currencyAndExpense[i + 1])));
        }

        return Models.PeriodAmounts.parseAll(new JSONObject().put("thisMonth", new JSONObject().put("amounts", list)));
    }
}
