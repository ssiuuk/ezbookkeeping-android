package com.ssiuuk.ezbookkeeping;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.color.MaterialColors;
import com.ssiuuk.ezbookkeeping.data.Formats;
import com.ssiuuk.ezbookkeeping.data.Models;

/** Small view helpers shared by the native screens. */
final class Ui {
    /** Bumped whenever data changes, so screens that were in the background reload when shown again. */
    static int dataVersion;

    private Ui() {
    }

    static void dataChanged() {
        dataVersion++;
    }

    static void bindAvatar(TextView avatar, String name, String hexColor) {
        int fallback = MaterialColors.getColor(avatar, com.google.android.material.R.attr.colorSecondary);
        avatar.setText(Formats.initial(name));
        avatar.setBackgroundTintList(ColorStateList.valueOf(Formats.color(hexColor, fallback)));
    }

    static int amountColor(Context context, int transactionType) {
        switch (transactionType) {
            case Models.TRANSACTION_INCOME:
                return ContextCompat.getColor(context, R.color.amount_income);
            case Models.TRANSACTION_EXPENSE:
                return ContextCompat.getColor(context, R.color.amount_expense);
            default:
                return ContextCompat.getColor(context, R.color.amount_transfer);
        }
    }

    static String transactionTitle(Context context, Models.Transaction transaction) {
        if (transaction.type == Models.TRANSACTION_MODIFY_BALANCE) {
            return context.getString(R.string.balance_change);
        }

        if (transaction.categoryName != null && !transaction.categoryName.isEmpty()) {
            return transaction.categoryName;
        }

        switch (transaction.type) {
            case Models.TRANSACTION_INCOME:
                return context.getString(R.string.income);
            case Models.TRANSACTION_TRANSFER:
                return context.getString(R.string.transfer);
            default:
                return context.getString(R.string.expense);
        }
    }

    static String transactionSubtitle(Context context, Models.Transaction transaction) {
        String account = transaction.sourceAccountName == null ? "" : transaction.sourceAccountName;

        if (transaction.type == Models.TRANSACTION_TRANSFER && transaction.destinationAccountName != null) {
            account = context.getString(R.string.transfer_to, account, transaction.destinationAccountName);
        }

        if (transaction.comment != null && !transaction.comment.isEmpty()) {
            return transaction.comment + " · " + account;
        }

        return account;
    }

    static String transactionAmount(Context context, Models.Transaction transaction) {
        if (transaction.hideAmount) {
            return context.getString(R.string.hidden_amount);
        }

        String currency = transaction.sourceCurrency;

        switch (transaction.type) {
            case Models.TRANSACTION_INCOME:
                return Formats.signedAmount(transaction.sourceAmount, currency, false);
            case Models.TRANSACTION_EXPENSE:
                return Formats.signedAmount(transaction.sourceAmount, currency, true);
            case Models.TRANSACTION_MODIFY_BALANCE:
                return Formats.signedAmount(transaction.sourceAmount, currency, transaction.sourceAmount < 0);
            default:
                return Formats.amount(transaction.sourceAmount, currency);
        }
    }

    static void bindTransaction(View row, Models.Transaction transaction) {
        Context context = row.getContext();
        TextView avatar = row.findViewById(R.id.avatar);
        String title = transactionTitle(context, transaction);
        bindAvatar(avatar, title, transaction.categoryColor);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        ((TextView) row.findViewById(R.id.subtitle)).setText(transactionSubtitle(context, transaction));

        TextView amount = row.findViewById(R.id.amount);
        amount.setText(transactionAmount(context, transaction));
        amount.setTextColor(amountColor(context, transaction.type));

        ((TextView) row.findViewById(R.id.meta)).setText(Formats.time(context, transaction.time * 1000));
    }
}
