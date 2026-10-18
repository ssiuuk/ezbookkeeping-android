package com.ssiuuk.ezbookkeeping.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Remembers which account was used last for each category and each kind of transaction,
 * per server and book, so a new transaction starts with the account people usually pick.
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
