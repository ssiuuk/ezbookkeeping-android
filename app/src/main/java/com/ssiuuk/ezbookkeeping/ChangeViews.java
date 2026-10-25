package com.ssiuuk.ezbookkeeping;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.ssiuuk.ezbookkeeping.data.Formats;
import com.ssiuuk.ezbookkeeping.data.LocalStore;
import com.ssiuuk.ezbookkeeping.data.Models;
import com.ssiuuk.ezbookkeeping.data.Sync;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** How a transaction saved on this phone but not yet on the server shows in a list, and what tapping it does. */
final class ChangeViews {
    private ChangeViews() {
    }

    /** The change as a transaction, with the names saved alongside it since the lists of accounts may be out of reach. */
    static Models.Transaction transactionOf(LocalStore.Change change) {
        boolean deleting = LocalStore.KIND_DELETE.equals(change.kind) && change.base != null;
        Models.Transaction transaction = Models.Transaction.parse(deleting ? change.base : change.body);

        if (!deleting) {
            transaction.categoryName = change.label("category");
            transaction.sourceAccountName = change.label("account");
            transaction.sourceCurrency = change.label("currency");
            transaction.destinationAccountName = emptyToNull(change.label("destinationAccount"));
            transaction.destinationCurrency = change.label("destinationCurrency");
        }

        transaction.id = change.transactionId;
        return transaction;
    }

    static void bind(View row, LocalStore.Change change) {
        Context context = row.getContext();
        Ui.bindTransaction(row, transactionOf(change));
        TextView meta = row.findViewById(R.id.meta);
        meta.setText(stateText(context, change));

        if (!change.isWaiting()) {
            meta.setTextColor(MaterialColors.getColor(meta, com.google.android.material.R.attr.colorError));
        }
    }

    static String stateText(Context context, LocalStore.Change change) {
        switch (change.state) {
            case LocalStore.STATE_CONFLICT:
                return context.getString(change.serverCopy == null ? R.string.sync_state_deleted_on_server : R.string.sync_state_conflict);
            case LocalStore.STATE_FAILED:
                return context.getString(R.string.sync_state_failed);
            default:
                return context.getString(LocalStore.KIND_DELETE.equals(change.kind) ? R.string.sync_state_delete_waiting : R.string.sync_state_waiting);
        }
    }

    /** Opens a waiting change for editing, or asks how to settle one the server didn't take. */
    static void open(Activity activity, LocalStore.Change change, Runnable changed) {
        if (LocalStore.STATE_CONFLICT.equals(change.state)) {
            askConflict(activity, change, changed);
        } else if (LocalStore.STATE_FAILED.equals(change.state)) {
            askFailed(activity, change, changed);
        } else if (LocalStore.KIND_DELETE.equals(change.kind)) {
            new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.sync_undo_delete_title)
                .setMessage(R.string.sync_undo_delete_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.conflict_keep_it, (dialog, which) -> settle(activity, change, false, changed))
                .show();
        } else {
            activity.startActivity(TransactionEditActivity.queuedIntent(activity, change.id));
        }
    }

    private static void askConflict(Activity activity, LocalStore.Change change, Runnable changed) {
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.conflict_title)
            .setNeutralButton(R.string.cancel, null);

        if (change.serverCopy == null) {
            // changed here, deleted there
            dialog.setMessage(R.string.conflict_deleted_on_server)
                .setPositiveButton(R.string.conflict_add_back, (d, which) -> settle(activity, change, true, changed))
                .setNegativeButton(R.string.conflict_leave_deleted, (d, which) -> settle(activity, change, false, changed));
        } else if (LocalStore.KIND_DELETE.equals(change.kind)) {
            // deleted here, changed there
            Models.Transaction before = change.base == null ? null : Models.Transaction.parse(change.base);
            String message = activity.getString(R.string.conflict_deleted_here);

            if (before != null) {
                message += "\n\n" + differenceLines(activity, before, Models.Transaction.parse(change.serverCopy), R.string.conflict_line_was_now);
            }

            dialog.setMessage(message)
                .setPositiveButton(R.string.delete, (d, which) -> settle(activity, change, true, changed))
                .setNegativeButton(R.string.conflict_keep_it, (d, which) -> settle(activity, change, false, changed));
        } else {
            String message = activity.getString(R.string.conflict_changed_on_server) + "\n\n"
                + differenceLines(activity, transactionOf(change), Models.Transaction.parse(change.serverCopy), R.string.conflict_line);
            dialog.setMessage(message)
                .setPositiveButton(R.string.conflict_keep_mine, (d, which) -> settle(activity, change, true, changed))
                .setNegativeButton(R.string.conflict_keep_servers, (d, which) -> settle(activity, change, false, changed));
        }

        dialog.show();
    }

    private static void askFailed(Activity activity, LocalStore.Change change, Runnable changed) {
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.sync_failed_title)
            .setMessage(TextUtils.isEmpty(change.message) ? activity.getString(R.string.sync_state_failed) : change.message)
            .setPositiveButton(R.string.retry, (d, which) -> settle(activity, change, true, changed))
            .setNegativeButton(R.string.sync_discard, (d, which) -> settle(activity, change, false, changed));

        if (!LocalStore.KIND_DELETE.equals(change.kind)) {
            // fix what the server didn't like, for example an account deleted meanwhile
            dialog.setNeutralButton(R.string.edit_transaction, (d, which) -> activity.startActivity(TransactionEditActivity.queuedIntent(activity, change.id)));
        }

        dialog.show();
    }

    private static void settle(Activity activity, LocalStore.Change change, boolean keepMine, Runnable changed) {
        if (keepMine) {
            Sync.keepMine(activity, change);
        } else {
            Sync.keepServers(activity, change);
        }

        Ui.dataChanged(activity.getApplicationContext());
        changed.run();
    }

    /** One line for each field that differs, such as "Amount: yours £12.00, the server's £15.00". */
    private static String differenceLines(Context context, Models.Transaction first, Models.Transaction second, int lineRes) {
        List<String> lines = new ArrayList<>();
        boolean otherFields = false;

        for (String key : Sync.differences(first.json, second.json)) {
            int labelRes = labelOf(key, first.type);

            if (labelRes == 0) {
                otherFields = true;
                continue;
            }

            lines.add(context.getString(lineRes, context.getString(labelRes), display(context, key, first), display(context, key, second)));
        }

        if (otherFields) {
            lines.add(context.getString(R.string.conflict_other_fields));
        }

        return TextUtils.join("\n", lines);
    }

    private static int labelOf(String key, int type) {
        switch (key) {
            case "type":
                return R.string.transaction_type;
            case "sourceAmount":
                return R.string.amount;
            case "destinationAmount":
                return R.string.received_amount;
            case "categoryId":
                return R.string.category;
            case "sourceAccountId":
                return type == Models.TRANSACTION_TRANSFER ? R.string.from_account : R.string.account;
            case "destinationAccountId":
                return R.string.to_account;
            case "time":
                return R.string.date;
            case "merchant":
                return R.string.merchant;
            case "comment":
                return R.string.description;
            default:
                // projects and tags are only known by id here
                return 0;
        }
    }

    private static String display(Context context, String key, Models.Transaction transaction) {
        switch (key) {
            case "type":
                return context.getString(transaction.type == Models.TRANSACTION_INCOME ? R.string.income
                    : transaction.type == Models.TRANSACTION_TRANSFER ? R.string.transfer : R.string.expense);
            case "sourceAmount":
                return Formats.amount(transaction.sourceAmount, transaction.sourceCurrency);
            case "destinationAmount":
                return Formats.amount(transaction.destinationAmount, transaction.destinationCurrency);
            case "categoryId":
                return orDash(transaction.categoryName);
            case "sourceAccountId":
                return orDash(transaction.sourceAccountName);
            case "destinationAccountId":
                return orDash(transaction.destinationAccountName);
            case "time":
                return Formats.date(context, transaction.time * 1000) + " " + Formats.time(context, transaction.time * 1000);
            case "merchant":
                return orDash(transaction.merchant);
            default:
                return orDash(transaction.comment);
        }
    }

    private static String orDash(String text) {
        return TextUtils.isEmpty(text) ? "—" : text;
    }

    private static String emptyToNull(String text) {
        return TextUtils.isEmpty(text) ? null : text;
    }

    static JSONObject labels(Models.Category category, Models.Account account, Models.Account destination) {
        JSONObject labels = new JSONObject();

        try {
            labels.put("category", category == null ? "" : category.name);
            labels.put("account", account == null ? "" : account.name);
            labels.put("currency", account == null ? "" : account.currency);
            labels.put("destinationAccount", destination == null ? "" : destination.name);
            labels.put("destinationCurrency", destination == null ? "" : destination.currency);
        } catch (org.json.JSONException ex) {
            // names are only for showing it
        }

        return labels;
    }
}
