package com.ssiuuk.ezbookkeeping.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Purchases picked up from payment notifications that are waiting to be reviewed and saved.
 * They stay on the phone until they are saved as transactions or dismissed.
 */
public final class PendingPayments {
    private static final String PREFERENCES = "pending_payments";
    private static final String KEY_ITEMS = "items";
    private static final int MAX_ITEMS = 100;
    // Wallet sometimes updates its notification for the same purchase; don't list it twice
    private static final long DUPLICATE_WINDOW = 5 * 60 * 1000;

    public static final class Item {
        public String id;
        public long time;
        public long amount;
        public String currency = "";
        public String merchant = "";
        public String card = "";
        public String cardKey = "";
        public boolean refund;
        public String source = "";

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                .put("id", id)
                .put("time", time)
                .put("amount", amount)
                .put("currency", currency)
                .put("merchant", merchant)
                .put("card", card)
                .put("cardKey", cardKey)
                .put("refund", refund)
                .put("source", source);
        }

        static Item fromJson(JSONObject json) {
            Item item = new Item();
            item.id = json.optString("id");
            item.time = json.optLong("time");
            item.amount = json.optLong("amount");
            item.currency = json.optString("currency", "");
            item.merchant = json.optString("merchant", "");
            item.card = json.optString("card", "");
            item.cardKey = json.optString("cardKey", "");
            item.refund = json.optBoolean("refund");
            item.source = json.optString("source", "");
            return item;
        }
    }

    private PendingPayments() {
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    /** Newest first. */
    public static synchronized List<Item> list(Context context) {
        List<Item> items = new ArrayList<>();

        try {
            JSONArray array = new JSONArray(preferences(context).getString(KEY_ITEMS, "[]"));

            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);

                if (json != null) {
                    items.add(Item.fromJson(json));
                }
            }
        } catch (JSONException ex) {
            // start over if the list was damaged
        }

        return items;
    }

    public static Item get(Context context, String id) {
        for (Item item : list(context)) {
            if (item.id.equals(id)) {
                return item;
            }
        }

        return null;
    }

    /** Adds a purchase, unless the same one was just added. Returns the new item, or null for a repeat. */
    public static synchronized Item add(Context context, PaymentNotification payment, long time, String source) {
        List<Item> items = list(context);

        for (Item existing : items) {
            if (existing.amount == payment.amount && existing.merchant.equals(payment.merchant)
                && existing.cardKey.equals(payment.cardKey()) && Math.abs(existing.time - time) < DUPLICATE_WINDOW) {
                return null;
            }
        }

        Item item = new Item();
        item.id = UUID.randomUUID().toString();
        item.time = time;
        item.amount = payment.amount;
        item.currency = payment.currency;
        item.merchant = payment.merchant;
        item.card = payment.card;
        item.cardKey = payment.cardKey();
        item.refund = payment.refund;
        item.source = source == null ? "" : source;
        items.add(0, item);

        while (items.size() > MAX_ITEMS) {
            items.remove(items.size() - 1);
        }

        save(context, items);
        return item;
    }

    public static synchronized void remove(Context context, String id) {
        List<Item> items = list(context);

        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(id)) {
                items.remove(i);
                save(context, items);
                return;
            }
        }
    }

    /** Puts a dismissed item back, for undo. */
    public static synchronized void restore(Context context, Item item) {
        List<Item> items = list(context);
        int index = 0;

        while (index < items.size() && items.get(index).time > item.time) {
            index++;
        }

        items.add(index, item);
        save(context, items);
    }

    private static void save(Context context, List<Item> items) {
        JSONArray array = new JSONArray();

        try {
            for (Item item : items) {
                array.put(item.toJson());
            }
        } catch (JSONException ex) {
            return;
        }

        preferences(context).edit().putString(KEY_ITEMS, array.toString()).apply();
    }
}
