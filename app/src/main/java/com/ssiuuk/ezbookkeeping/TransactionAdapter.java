package com.ssiuuk.ezbookkeeping;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ssiuuk.ezbookkeeping.data.Formats;
import com.ssiuuk.ezbookkeeping.data.Models;

import java.util.ArrayList;
import java.util.List;

/** Transactions grouped under a header for each day. */
class TransactionAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    interface OnTransactionClick {
        void onClick(Models.Transaction transaction);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_TRANSACTION = 1;

    private final List<Object> rows = new ArrayList<>();
    private final List<Models.Transaction> transactions = new ArrayList<>();
    private final OnTransactionClick onClick;

    TransactionAdapter(OnTransactionClick onClick) {
        this.onClick = onClick;
    }

    void setTransactions(List<Models.Transaction> items) {
        transactions.clear();
        transactions.addAll(items);
        rebuild();
    }

    void addTransactions(List<Models.Transaction> items) {
        transactions.addAll(items);
        rebuild();
    }

    boolean isEmpty() {
        return transactions.isEmpty();
    }

    private void rebuild() {
        rows.clear();
        long currentDay = Long.MIN_VALUE;

        for (Models.Transaction transaction : transactions) {
            long day = Formats.startOfDay(transaction.time * 1000);

            if (day != currentDay) {
                rows.add(day);
                currentDay = day;
            }

            rows.add(transaction);
        }

        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position) instanceof Long ? TYPE_HEADER : TYPE_TRANSACTION;
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == TYPE_HEADER ? R.layout.item_header : R.layout.item_transaction;
        View view = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new RecyclerView.ViewHolder(view) {
        };
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object row = rows.get(position);

        if (row instanceof Long) {
            long day = (Long) row;
            ((TextView) holder.itemView.findViewById(R.id.title)).setText(Formats.dayHeader(holder.itemView.getContext(), day / 1000));
            ((TextView) holder.itemView.findViewById(R.id.value)).setText(dayTotal(day));
            return;
        }

        Models.Transaction transaction = (Models.Transaction) row;
        Ui.bindTransaction(holder.itemView, transaction);
        holder.itemView.setOnClickListener(view -> onClick.onClick(transaction));
    }

    /** The day's spending, shown next to the date when all of it is in one currency. */
    private String dayTotal(long day) {
        long expense = 0;
        String currency = null;

        for (Models.Transaction transaction : transactions) {
            if (Formats.startOfDay(transaction.time * 1000) != day || transaction.type != Models.TRANSACTION_EXPENSE || transaction.hideAmount) {
                continue;
            }

            if (currency != null && !currency.equals(transaction.sourceCurrency)) {
                return "";
            }

            currency = transaction.sourceCurrency;
            expense += transaction.sourceAmount;
        }

        return currency == null || expense == 0 ? "" : "−" + Formats.amount(expense, currency);
    }
}
