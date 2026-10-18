package com.ssiuuk.ezbookkeeping;

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
import com.ssiuuk.ezbookkeeping.data.Models;
import com.ssiuuk.ezbookkeeping.data.Server;
import com.ssiuuk.ezbookkeeping.data.ServerStore;

import java.util.Map;

/** All transactions, newest first, loading more while scrolling. Can be limited to one account. */
public class TransactionsFragment extends Fragment implements Reloadable {
    static final String ARG_ACCOUNT_ID = "account_id";
    private static final int PAGE_SIZE = 50;

    private TransactionAdapter adapter;
    private SwipeRefreshLayout refresh;
    private View emptyView;
    private String accountId;
    private String keyword;
    private String nextTimeSequenceId;
    private boolean loading;
    private int requestId;
    private int loadedVersion = -1;

    static TransactionsFragment forAccount(String accountId) {
        TransactionsFragment fragment = new TransactionsFragment();
        Bundle args = new Bundle();
        args.putString(ARG_ACCOUNT_ID, accountId);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_list, container, false);
        accountId = getArguments() == null ? null : getArguments().getString(ARG_ACCOUNT_ID);

        refresh = view.findViewById(R.id.refresh);
        emptyView = view.findViewById(R.id.empty);
        RecyclerView list = view.findViewById(R.id.list);
        LinearLayoutManager layoutManager = new LinearLayoutManager(requireContext());
        list.setLayoutManager(layoutManager);
        adapter = new TransactionAdapter(transaction -> startActivity(TransactionEditActivity.editIntent(requireContext(), transaction.id)));
        list.setAdapter(adapter);

        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0 && !loading && nextTimeSequenceId != null
                    && layoutManager.findLastVisibleItemPosition() >= adapter.getItemCount() - 10) {
                    load(false);
                }
            }
        });

        refresh.setOnRefreshListener(() -> load(true));

        ((ImageView) view.findViewById(R.id.empty_icon)).setImageResource(R.drawable.ic_list);
        ((TextView) view.findViewById(R.id.empty_title)).setText(R.string.no_transactions);
        ((TextView) view.findViewById(R.id.empty_detail)).setText(R.string.no_transactions_hint);
        MaterialButton emptyAction = view.findViewById(R.id.empty_action);
        emptyAction.setVisibility(View.VISIBLE);
        emptyAction.setText(R.string.add_transaction);
        emptyAction.setOnClickListener(v -> startActivity(TransactionEditActivity.addIntent(requireContext(), accountId == null ? null : accountId.split(",")[0])));
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
            load(true);
        }
    }

    void setKeyword(String newKeyword) {
        String normalized = newKeyword == null || newKeyword.trim().isEmpty() ? null : newKeyword.trim();

        if ((normalized == null && keyword == null) || (normalized != null && normalized.equals(keyword))) {
            return;
        }

        keyword = normalized;

        if (adapter != null) {
            load(true);
        }
    }

    private void load(boolean reset) {
        Server server = ServerStore.get(requireContext()).getActiveServer();

        if (server == null) {
            return;
        }

        if (reset) {
            nextTimeSequenceId = null;
            loadedVersion = Ui.dataVersion;
            refresh.setRefreshing(true);
        }

        loading = true;
        int thisRequest = ++requestId;
        Map<String, String> query = Api.query(
            "count", String.valueOf(PAGE_SIZE),
            "page", "1",
            "max_time", reset || nextTimeSequenceId == null ? "0" : nextTimeSequenceId,
            "min_time", "0",
            "account_ids", accountId,
            "keyword", keyword);

        new Api(server).get("v1/transactions/list.json", query, Models.TransactionPage::parse, new Api.Callback<Models.TransactionPage>() {
            @Override
            public void onSuccess(Models.TransactionPage page) {
                if (thisRequest != requestId || !isAdded()) {
                    return;
                }

                loading = false;
                refresh.setRefreshing(false);
                nextTimeSequenceId = page.nextTimeSequenceId;

                if (reset) {
                    adapter.setTransactions(page.items);
                } else {
                    adapter.addTransactions(page.items);
                }

                emptyView.setVisibility(adapter.isEmpty() ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onError(ApiException error) {
                if (thisRequest != requestId || !isAdded()) {
                    return;
                }

                loading = false;
                refresh.setRefreshing(false);
                Snackbar.make(refresh, error.getMessage(), Snackbar.LENGTH_LONG)
                    .setAction(R.string.retry, v -> load(reset))
                    .show();
            }
        });
    }
}
