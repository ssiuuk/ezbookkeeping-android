package com.ssiuuk.ezbookkeeping.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Remembers which account was used last for each category and each kind of transaction,
 * per server and book, so a new transaction starts with the account people usually pick.
 * It also keeps which account each payment card belongs to and the category used at each shop.
 */
public final class RecentChoices {
    private final SharedPreferences preferences;
    private final String prefix;

    public RecentChoices(Context context, Server server, String bookId) {
        preferences = context.getApplicationContext().getSharedPreferences("recent_choices", Context.MODE_PRIVATE);
        prefix = server.id + "|" + (bookId == null ? "" : bookId) + "|";
    }

    public String accountForCategory(String categoryId, boolean destination) {
        return categoryId == null ? null : preferences.getString(prefix + (destination ? "to|" : "from|") + categoryId, null);
    }

    public String accountForType(int type, boolean destination) {
        return preferences.getString(prefix + (destination ? "to-type|" : "from-type|") + type, null);
    }

    public String accountForCard(String cardKey) {
        return cardKey == null || cardKey.isEmpty() ? null : preferences.getString(prefix + "card|" + cardKey, null);
    }

    public String categoryForMerchant(String merchant) {
        return merchant == null || merchant.isEmpty() ? null : preferences.getString(prefix + "merchant|" + merchantKey(merchant), null);
    }

    /** Remembers the account a payment card belongs to, so the next purchase with it picks the same account. */
    public void rememberCard(String cardKey, String accountId) {
        if (cardKey != null && !cardKey.isEmpty()) {
            preferences.edit().putString(prefix + "card|" + cardKey, accountId).apply();
        }
    }

    /** Remembers the category used at a shop, so the next purchase there starts with it. */
    public void rememberMerchant(String merchant, String categoryId) {
        if (merchant != null && !merchant.trim().isEmpty()) {
            preferences.edit().putString(prefix + "merchant|" + merchantKey(merchant), categoryId).apply();
        }
    }

    private static String merchantKey(String merchant) {
        return merchant.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public void remember(int type, String categoryId, String accountId, String destinationAccountId) {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString(prefix + "from|" + categoryId, accountId);
        editor.putString(prefix + "from-type|" + type, accountId);

        if (destinationAccountId != null) {
            editor.putString(prefix + "to|" + categoryId, destinationAccountId);
            editor.putString(prefix + "to-type|" + type, destinationAccountId);
        }

        editor.apply();
    }
}
