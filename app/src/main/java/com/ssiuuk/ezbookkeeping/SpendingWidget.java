package com.ssiuuk.ezbookkeeping;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;

import androidx.core.content.ContextCompat;

import com.ssiuuk.ezbookkeeping.data.Api;
import com.ssiuuk.ezbookkeeping.data.ApiException;
import com.ssiuuk.ezbookkeeping.data.Formats;
import com.ssiuuk.ezbookkeeping.data.Languages;
import com.ssiuuk.ezbookkeeping.data.Models;
import com.ssiuuk.ezbookkeeping.data.PendingPayments;
import com.ssiuuk.ezbookkeeping.data.Server;
import com.ssiuuk.ezbookkeeping.data.ServerStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Home screen widget: a period's spending, a few other figures, the latest transactions and quick ways to add one.
 * One row shows the spending and an add button; two rows add income and other periods; three and four rows add
 * the latest transactions and buttons for income, transfers and Wallet purchases to review.
 */
public class SpendingWidget extends AppWidgetProvider {
    static final String ACTION_REFRESH = "com.ssiuuk.ezbookkeeping.widget.REFRESH";

    /** The periods a widget can show, as named by v1/transactions/amounts.json. */
    static final String[] PERIODS = { "today", "thisWeek", "thisMonth", "thisYear" };
    static final String BOOK_PERSONAL = "personal";

    private static final String[] FETCHED_PERIODS = { "today", "thisWeek", "thisMonth", "lastMonth", "thisYear" };
    private static final int RECENT_COUNT = 5;
    private static final String PREFS = "widgets";

    // sizes in dp; Android 12 gives a widget n cells about 70n - 30 dp
    private static final int ONE_ROW_MAX_HEIGHT = 100;
    private static final int INCOME_MIN_WIDTH = 250;
    private static final int FOOTER_MIN_HEIGHT = 152;
    private static final int ROWS_MIN_HEIGHT = 160;
    private static final int ROW_HEIGHT = 36;
    private static final int[][] SIZES = { { 110, 40 }, { 250, 40 }, { 110, 110 }, { 110, 152 }, { 110, 196 }, { 110, 232 }, { 110, 268 }, { 110, 304 }, { 110, 340 } };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable REFRESH_ALL = SpendingWidget::refreshAllNow;
    private static Context appContext;

    /** What one widget shows, chosen in SpendingWidgetConfigureActivity. */
    static final class Settings {
        String period = "thisMonth";
        /** "" follows the book chosen in the app, BOOK_PERSONAL is the user's own book, anything else a shared book's id. */
        String book = "";
        String bookName = "";
        boolean hideAmounts;

        static Settings load(Context context, int widgetId) {
            SharedPreferences prefs = prefs(context);
            Settings settings = new Settings();
            settings.period = prefs.getString(widgetId + ".period", settings.period);
            settings.book = prefs.getString(widgetId + ".book", settings.book);
            settings.bookName = prefs.getString(widgetId + ".bookName", settings.bookName);
            settings.hideAmounts = prefs.getBoolean(widgetId + ".hideAmounts", false);
            return settings;
        }

        void save(Context context, int widgetId) {
            prefs(context).edit()
                .putString(widgetId + ".period", period)
                .putString(widgetId + ".book", book)
                .putString(widgetId + ".bookName", bookName)
                .putBoolean(widgetId + ".hideAmounts", hideAmounts)
                .remove(widgetId + ".data")
                .apply();
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        PendingResult result = goAsync();
        refresh(context, widgetIds, result::finish);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int widgetId, Bundle newOptions) {
        // resized: show what is already known in the new size
        render(context, widgetId);
    }

    @Override
    public void onDeleted(Context context, int[] widgetIds) {
        SharedPreferences.Editor editor = prefs(context).edit();

        for (int widgetId : widgetIds) {
            for (String key : new String[] { "period", "book", "bookName", "hideAmounts", "data" }) {
                editor.remove(widgetId + "." + key);
            }
        }

        editor.apply();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_REFRESH.equals(intent.getAction())) {
            PendingResult result = goAsync();
            refresh(context, widgetIds(context), result::finish);
            return;
        }

        super.onReceive(context, intent);
    }

    /** Fetches and shows the figures of every widget again, once for changes made in quick succession. */
    static void refreshAll(Context context) {
        appContext = context.getApplicationContext();
        MAIN.removeCallbacks(REFRESH_ALL);
        MAIN.postDelayed(REFRESH_ALL, 500);
    }

    private static void refreshAllNow() {
        int[] widgetIds = widgetIds(appContext);

        if (widgetIds.length > 0) {
            refresh(appContext, widgetIds, () -> { });
        }
    }

    static void refresh(Context context, int[] widgetIds, Runnable done) {
        Context app = context.getApplicationContext();
        Server server = ServerStore.get(app).getActiveServer();

        if (widgetIds.length == 0 || server == null || !server.isSignedIn()) {
            for (int widgetId : widgetIds) {
                render(app, widgetId);
            }

            done.run();
            return;
        }

        int[] remaining = { widgetIds.length };

        for (int widgetId : widgetIds) {
            // show the last figures (or that they are loading) in the right language and size straight away
            render(app, widgetId);
            fetch(app, server, widgetId, () -> {
                render(app, widgetId);

                if (--remaining[0] == 0) {
                    done.run();
                }
            });
        }
    }

    private static int[] widgetIds(Context context) {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context, SpendingWidget.class));
    }

    /** Loads the periods' totals, exchange rates and latest transactions of the widget's book and keeps them for render. */
    private static void fetch(Context context, Server server, int widgetId, Runnable done) {
        Settings settings = Settings.load(context, widgetId);
        Api api = new Api(server).inBackground();

        if (BOOK_PERSONAL.equals(settings.book)) {
            api.withBook(null);
        } else if (!settings.book.isEmpty()) {
            api.withBook(settings.book);
        }

        Context text = Languages.localized(context);
        String currency = server.getDefaultCurrency();
        Object[] results = new Object[3];
        String[] error = { null };
        int[] pending = { 3 };

        Runnable finished = () -> {
            if (--pending[0] > 0) {
                return;
            }

            JSONObject data = loadData(context, widgetId);

            try {
                if (data == null || !currency.equals(data.optString("currency"))) {
                    data = new JSONObject().put("currency", currency);
                }

                if (results[0] != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Models.PeriodAmounts> amounts = (Map<String, Models.PeriodAmounts>) results[0];
                    Models.ExchangeRates rates = results[1] != null ? (Models.ExchangeRates) results[1] : new Models.ExchangeRates();
                    Set<String> unconverted = new HashSet<>();
                    JSONObject totals = new JSONObject();

                    for (String period : FETCHED_PERIODS) {
                        Models.PeriodAmounts amount = amounts.get(period);
                        long[] total = amount == null ? new long[] { 0, 0 } : amount.total(currency, rates, unconverted);
                        totals.put(period, new JSONArray().put(total[0]).put(total[1]));
                    }

                    unconverted.remove(currency);
                    data.put("totals", totals);
                    data.put("unconverted", !unconverted.isEmpty());
                }

                if (results[2] != null) {
                    JSONArray recent = new JSONArray();

                    for (Models.Transaction transaction : ((Models.TransactionPage) results[2]).items) {
                        recent.put(new JSONObject()
                            .put("id", transaction.id)
                            .put("type", transaction.type)
                            .put("title", Ui.transactionTitle(text, transaction))
                            .put("subtitle", Formats.dayHeader(text, transaction.time) + " · " + Ui.transactionSubtitle(text, transaction))
                            .put("amount", Ui.transactionAmount(text, transaction)));
                    }

                    data.put("recent", recent);
                }

                if (error[0] == null) {
                    data.put("updated", System.currentTimeMillis());
                    data.remove("error");
                } else {
                    data.put("error", error[0]);
                }

                prefs(context).edit().putString(widgetId + ".data", data.toString()).apply();
            } catch (JSONException ex) {
                // keep the figures already shown
            }

            done.run();
        };

        api.get("v1/transactions/amounts.json", Api.query("query", Models.PeriodAmounts.query(server, FETCHED_PERIODS), "use_transaction_timezone", "false"),
            Models.PeriodAmounts::parseAll, new Api.Callback<Map<String, Models.PeriodAmounts>>() {
                @Override
                public void onSuccess(Map<String, Models.PeriodAmounts> result) {
                    results[0] = result;
                    finished.run();
                }

                @Override
                public void onError(ApiException ex) {
                    error[0] = ex.getMessage();
                    finished.run();
                }
            });

        api.get("v1/exchange_rates/latest.json", null, Models.ExchangeRates::parse, new Api.Callback<Models.ExchangeRates>() {
            @Override
            public void onSuccess(Models.ExchangeRates result) {
                results[1] = result;
                finished.run();
            }

            @Override
            public void onError(ApiException ex) {
                // without rates, only the default currency is counted
                finished.run();
            }
        });

        api.get("v1/transactions/list.json", Api.query("count", String.valueOf(RECENT_COUNT), "page", "1", "max_time", "0", "min_time", "0"),
            Models.TransactionPage::parse, new Api.Callback<Models.TransactionPage>() {
                @Override
                public void onSuccess(Models.TransactionPage result) {
                    results[2] = result;
                    finished.run();
                }

                @Override
                public void onError(ApiException ex) {
                    error[0] = ex.getMessage();
                    finished.run();
                }
            });
    }

    static void render(Context context, int widgetId) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        RemoteViews views;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // the launcher picks the largest layout that fits, also while the widget is being resized
            Map<SizeF, RemoteViews> layouts = new LinkedHashMap<>();

            for (int[] size : SIZES) {
                layouts.put(new SizeF(size[0], size[1]), build(context, widgetId, size[0], size[1]));
            }

            views = new RemoteViews(layouts);
        } else {
            Bundle options = manager.getAppWidgetOptions(widgetId);
            // portrait sizes: the narrowest width and the tallest height
            int width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            int height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
            views = build(context, widgetId, width > 0 ? width : 250, height > 0 ? height : 110);
        }

        manager.updateAppWidget(widgetId, views);
    }

    private static RemoteViews build(Context context, int widgetId, int width, int height) {
        Context text = Languages.localized(context);
        Settings settings = Settings.load(context, widgetId);
        Server server = ServerStore.get(context).getActiveServer();
        boolean signedIn = server != null && server.isSignedIn();
        JSONObject data = signedIn ? loadData(context, widgetId) : null;
        JSONObject totals = data == null ? null : data.optJSONObject("totals");
        String currency = data == null ? null : data.optString("currency");
        // shown until the first figures arrive, or when they couldn't be loaded
        String missing = data != null && data.has("error") ? "—" : "…";
        boolean oneRow = height < ONE_ROW_MAX_HEIGHT;
        String packageName = context.getPackageName();

        RemoteViews views = new RemoteViews(packageName, oneRow ? R.layout.widget_small : R.layout.widget_full);
        views.setOnClickPendingIntent(R.id.summary, launch(context, widgetId, 0, new Intent(Intent.ACTION_MAIN)));
        views.setOnClickPendingIntent(R.id.add, launch(context, widgetId, 1, addIntent(Models.TRANSACTION_EXPENSE)));
        views.setContentDescription(R.id.add, text.getString(R.string.add_transaction));

        if (!signedIn) {
            views.setTextViewText(R.id.label, text.getString(R.string.app_name));
            views.setTextViewText(R.id.spent, text.getString(R.string.widget_sign_in));
            views.setTextViewTextSize(R.id.spent, android.util.TypedValue.COMPLEX_UNIT_SP, 14);
            views.setViewVisibility(R.id.add, View.GONE);

            if (!oneRow) {
                views.setViewVisibility(R.id.stats, View.GONE);
                views.setViewVisibility(R.id.footer, View.GONE);
            }

            return views;
        }

        String label = periodName(text, settings.period);
        String book = bookName(text, settings, server);
        views.setTextViewText(R.id.label, book == null ? label : label + " · " + book);
        views.setTextViewText(R.id.spent, totals == null ? missing : money(text, settings, expense(totals, settings.period), currency));

        if (oneRow) {
            if (width >= INCOME_MIN_WIDTH && totals != null) {
                views.setViewVisibility(R.id.income_column, View.VISIBLE);
                views.setTextViewText(R.id.income_label, text.getString(R.string.income));
                views.setTextViewText(R.id.income, money(text, settings, income(totals, settings.period), currency));
            }

            return views;
        }

        // the other figures: income of the period, and spending of a shorter and a longer period
        String[] others = otherPeriods(settings.period);
        views.setTextViewText(R.id.stat1_label, text.getString(R.string.income));
        views.setTextViewText(R.id.stat1, totals == null ? missing : money(text, settings, income(totals, settings.period), currency));
        views.setTextViewText(R.id.stat2_label, periodName(text, others[0]));
        views.setTextViewText(R.id.stat2, totals == null ? missing : money(text, settings, expense(totals, others[0]), currency));
        views.setTextViewText(R.id.stat3_label, periodName(text, others[1]));
        views.setTextViewText(R.id.stat3, totals == null ? missing : money(text, settings, expense(totals, others[1]), currency));

        int rows = Math.max(0, Math.min(RECENT_COUNT, (height - ROWS_MIN_HEIGHT) / ROW_HEIGHT));
        JSONArray recent = data == null ? null : data.optJSONArray("recent");
        views.removeAllViews(R.id.recent_list);

        for (int i = 0; recent != null && i < rows && i < recent.length(); i++) {
            JSONObject item = recent.optJSONObject(i);
            RemoteViews row = new RemoteViews(packageName, R.layout.widget_row);
            row.setTextViewText(R.id.title, item.optString("title"));
            row.setTextViewText(R.id.subtitle, item.optString("subtitle"));
            row.setTextViewText(R.id.amount, settings.hideAmounts ? text.getString(R.string.hidden_amount) : item.optString("amount"));
            setAmountColor(context, row, item.optInt("type"));

            Intent edit = new Intent(LaunchActivity.ACTION_EDIT_TRANSACTION).putExtra(LaunchActivity.EXTRA_TRANSACTION_ID, item.optString("id"));
            row.setOnClickPendingIntent(R.id.row, launch(context, widgetId, 8 + i, edit));
            views.addView(R.id.recent_list, row);
        }

        boolean footer = height >= FOOTER_MIN_HEIGHT;
        views.setViewVisibility(R.id.recent_list, rows > 0 ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.footer, footer ? View.VISIBLE : View.GONE);

        if (footer) {
            views.setTextViewText(R.id.add_income, text.getString(R.string.income));
            views.setOnClickPendingIntent(R.id.add_income, launch(context, widgetId, 2, addIntent(Models.TRANSACTION_INCOME)));
            views.setTextViewText(R.id.add_transfer, text.getString(R.string.transfer));
            views.setOnClickPendingIntent(R.id.add_transfer, launch(context, widgetId, 3, addIntent(Models.TRANSACTION_TRANSFER)));

            // Wallet purchases waiting on the Overview
            List<PendingPayments.Item> pending = PendingPayments.list(context);
            views.setViewVisibility(R.id.review, pending.isEmpty() ? View.GONE : View.VISIBLE);
            views.setTextViewText(R.id.review, text.getResources().getQuantityString(R.plurals.widget_review_count, pending.size(), pending.size()));
            views.setOnClickPendingIntent(R.id.review, launch(context, widgetId, 4, new Intent(Intent.ACTION_MAIN)));

            String updated = "";

            if (data != null && data.has("error")) {
                updated = text.getString(R.string.widget_update_failed);
            } else if (data != null && data.has("updated")) {
                updated = Formats.time(text, data.optLong("updated"));
            }

            views.setTextViewText(R.id.updated, updated);
            views.setContentDescription(R.id.refresh, text.getString(R.string.refresh));
            Intent refresh = new Intent(context, SpendingWidget.class).setAction(ACTION_REFRESH);
            views.setOnClickPendingIntent(R.id.refresh, PendingIntent.getBroadcast(context, 0, refresh, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }

        return views;
    }

    private static void setAmountColor(Context context, RemoteViews row, int type) {
        int color = type == Models.TRANSACTION_INCOME ? R.color.amount_income
            : type == Models.TRANSACTION_EXPENSE ? R.color.amount_expense
            : R.color.md_on_surface_variant;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // resolved by the launcher, so it follows its light or dark theme
            row.setColorStateList(R.id.amount, "setTextColor", color);
        } else {
            row.setTextColor(R.id.amount, ContextCompat.getColor(context, color));
        }
    }

    /** The two periods shown next to the income: a shorter and a longer one, or last month next to this month. */
    static String[] otherPeriods(String period) {
        switch (period) {
            case "today":
                return new String[] { "thisWeek", "thisMonth" };
            case "thisMonth":
                return new String[] { "today", "lastMonth" };
            default:
                return new String[] { "today", "thisMonth" };
        }
    }

    static String periodName(Context context, String period) {
        switch (period) {
            case "today":
                return context.getString(R.string.today);
            case "thisWeek":
                return context.getString(R.string.this_week);
            case "lastMonth":
                return context.getString(R.string.last_month);
            case "thisYear":
                return context.getString(R.string.this_year);
            default:
                return context.getString(R.string.this_month);
        }
    }

    /** The book's name when the widget shows another book than the user's own. */
    private static String bookName(Context context, Settings settings, Server server) {
        if (settings.book.isEmpty()) {
            return server.bookId != null ? server.bookName : null;
        }

        return BOOK_PERSONAL.equals(settings.book) ? null : settings.bookName;
    }

    private static long income(JSONObject totals, String period) {
        JSONArray total = totals.optJSONArray(period);
        return total == null ? 0 : total.optLong(0);
    }

    private static long expense(JSONObject totals, String period) {
        JSONArray total = totals.optJSONArray(period);
        return total == null ? 0 : total.optLong(1);
    }

    private static String money(Context context, Settings settings, long amount, String currency) {
        return settings.hideAmounts ? context.getString(R.string.hidden_amount) : Formats.amount(amount, currency);
    }

    private static Intent addIntent(int type) {
        return new Intent(LaunchActivity.ACTION_ADD_TRANSACTION).putExtra(LaunchActivity.EXTRA_TYPE, type);
    }

    /** Opens the app through LaunchActivity, which signs in or picks a server first when needed. */
    private static PendingIntent launch(Context context, int widgetId, int slot, Intent intent) {
        intent.setClass(context, LaunchActivity.class);
        // a different address per button, so their extras don't replace each other's
        intent.setData(Uri.parse("ezbookkeeping-widget://" + widgetId + "/" + slot));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, widgetId * 16 + slot, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static JSONObject loadData(Context context, int widgetId) {
        String json = prefs(context).getString(widgetId + ".data", null);

        try {
            return json == null ? null : new JSONObject(json);
        } catch (JSONException ex) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
