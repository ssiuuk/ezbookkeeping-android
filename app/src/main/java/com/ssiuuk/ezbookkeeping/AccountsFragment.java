package com.ssiuuk.ezbookkeeping;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.ssiuuk.ezbookkeeping.data.Api;
import com.ssiuuk.ezbookkeeping.data.ApiException;
import com.ssiuuk.ezbookkeeping.data.Formats;
import com.ssiuuk.ezbookkeeping.data.Models;
import com.ssiuuk.ezbookkeeping.data.Server;
import com.ssiuuk.ezbookkeeping.data.ServerStore;

import java.util.ArrayList;
import java.util.List;

/** Accounts grouped by kind, with their balances. Tapping one shows its transactions. */
public class AccountsFragment extends Fragment implements Reloadable {
    private static final int[] CATEGORY_ORDER = { 1, 2, 8, 3, 4, 5, 6, 7, 9 };

    private final List<Object> rows = new ArrayList<>();
    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
    private SwipeRefreshLayout refresh;
    private View emptyView;
    private int loadedVersion = -1;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_list, container, false);
        refresh = view.findViewById(R.id.refresh);
        emptyView = view.findViewById(R.id.empty);
        RecyclerView list = view.findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new AccountAdapter();
        list.setAdapter(adapter);
        refresh.setOnRefreshListener(this::load);

        ((ImageView) view.findViewById(R.id.empty_icon)).setImageResource(R.drawable.ic_wallet);
        ((TextView) view.findViewById(R.id.empty_title)).setText(R.string.no_accounts);
        ((TextView) view.findViewById(R.id.empty_detail)).setText(R.string.no_accounts_hint);
        MaterialButton emptyAction = view.findViewById(R.id.empty_action);
        emptyAction.setVisibility(View.VISIBLE);
        emptyAction.setText(R.string.add_account);
        emptyAction.setOnClickListener(v -> startActivity(WebActivity.intent(requireContext(), "/account/add")));
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadIfStale();
    }

    @Override
    public void reloadIfStale() {
        if (loadedVersion != Ui.dataVersion && isAdded()) {
            load();
        }
    }

    private void load() {
        Server server = ServerStore.get(requireContext()).getActiveServer();

        if (server == null) {
            return;
        }

        loadedVersion = Ui.dataVersion;
        refresh.setRefreshing(true);
        new Api(server).get("v1/accounts/list.json", Api.query("visible_only", "true"), Models.Account::parseList, new Api.Callback<List<Models.Account>>() {
            @Override
            public void onSuccess(List<Models.Account> accounts) {
                if (!isAdded()) {
                    return;
                }

                refresh.setRefreshing(false);
                buildRows(accounts);
            }

            @Override
            public void onError(ApiException error) {
                if (!isAdded()) {
                    return;
                }

                refresh.setRefreshing(false);
                Snackbar.make(refresh, error.getMessage(), Snackbar.LENGTH_LONG).setAction(R.string.retry, v -> load()).show();
            }
        });
    }

    private void buildRows(List<Models.Account> accounts) {
        rows.clear();
        List<Integer> categories = new ArrayList<>();

        for (int category : CATEGORY_ORDER) {
            categories.add(category);
        }

        for (Models.Account account : accounts) {
            if (!categories.contains(account.category)) {
                categories.add(account.category);
            }
        }

        for (int category : categories) {
            List<Models.Account> group = new ArrayList<>();

            for (Models.Account account : accounts) {
                if (account.category == category && !account.hidden) {
                    group.add(account);
                }
            }

            if (group.isEmpty()) {
                continue;
            }

            rows.add(categoryName(category));
            rows.addAll(group);
        }

        adapter.notifyDataSetChanged();
        emptyView.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private String categoryName(int category) {
        switch (category) {
            case 1:
                return getString(R.string.account_category_cash);
            case 2:
                return getString(R.string.account_category_checking);
            case 3:
                return getString(R.string.account_category_credit_card);
            case 4:
                return getString(R.string.account_category_virtual);
            case 5:
                return getString(R.string.account_category_debt);
            case 6:
                return getString(R.string.account_category_receivables);
            case 7:
                return getString(R.string.account_category_investment);
            case 8:
                return getString(R.string.account_category_savings);
            case 9:
                return getString(R.string.account_category_deposit);
            default:
                return getString(R.string.account_category_other);
        }
    }

    private class AccountAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof String ? 0 : 1;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layout = viewType == 0 ? R.layout.item_header : R.layout.item_transaction;
            return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(layout, parent, false)) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object row = rows.get(position);
            View view = holder.itemView;

            if (row instanceof String) {
                ((TextView) view.findViewById(R.id.title)).setText((String) row);
                ((TextView) view.findViewById(R.id.value)).setText("");
                return;
            }

            Models.Account account = (Models.Account) row;
            Ui.bindAvatar(view.findViewById(R.id.avatar), account.name, account.color);
            ((TextView) view.findViewById(R.id.title)).setText(account.name);

            String subtitle;

            if (account.hasSubAccounts()) {
                subtitle = getString(R.string.sub_accounts, account.subAccounts.size());
            } else if (account.sharedBy != null && !account.sharedBy.isEmpty()) {
                subtitle = getString(R.string.book_owner, account.sharedBy);
            } else {
                subtitle = account.currency;
            }

            ((TextView) view.findViewById(R.id.subtitle)).setText(subtitle);

            String currency = account.hasSubAccounts() && !account.subAccounts.isEmpty() ? account.subAccounts.get(0).currency : account.currency;
            long balance = account.getTotalBalance();
            TextView amount = view.findViewById(R.id.amount);
            amount.setText(Formats.amount(balance, currency));
            amount.setTextColor(Ui.amountColor(requireContext(), balance < 0 ? Models.TRANSACTION_EXPENSE : Models.TRANSACTION_TRANSFER));
            ((TextView) view.findViewById(R.id.meta)).setText("");
            List<String> ids = new ArrayList<>();

            if (account.hasSubAccounts()) {
                for (Models.Account sub : account.subAccounts) {
                    ids.add(sub.id);
                }
            } else {
                ids.add(account.id);
            }

            view.setOnClickListener(v -> startActivity(new Intent(requireContext(), AccountTransactionsActivity.class)
                .putExtra(AccountTransactionsActivity.EXTRA_ACCOUNT_ID, android.text.TextUtils.join(",", ids))
                .putExtra(AccountTransactionsActivity.EXTRA_ACCOUNT_NAME, account.name)));
        }
    }
}
