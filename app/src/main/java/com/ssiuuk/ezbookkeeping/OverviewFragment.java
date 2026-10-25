package com.ssiuuk.ezbookkeeping;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.snackbar.Snackbar;
import com.ssiuuk.ezbookkeeping.data.Api;
import com.ssiuuk.ezbookkeeping.data.ApiException;
import com.ssiuuk.ezbookkeeping.data.Formats;
import com.ssiuuk.ezbookkeeping.data.LocalStore;
import com.ssiuuk.ezbookkeeping.data.Models;
import com.ssiuuk.ezbookkeeping.data.PendingPayments;
import com.ssiuuk.ezbookkeeping.data.Server;
import com.ssiuuk.ezbookkeeping.data.ServerStore;
import com.ssiuuk.ezbookkeeping.data.Sync;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Spending this month and other periods, net assets and the latest transactions. */
public class OverviewFragment extends Fragment implements Reloadable {
    private static final String[] PERIODS = { "today", "thisWeek", "thisMonth", "lastMonth", "thisYear" };
    private static final int RECENT_COUNT = 5;

    private SwipeRefreshLayout refresh;
    private TextView monthExpense;
    private TextView monthIncome;
    private TextView lastMonthExpense;
    private TextView otherCurrencies;
    private LinearLayout periods;
    private TextView netAssets;
    private TextView assetsDetail;
    private LinearLayout recentList;
    private View reviewCard;
    private TextView reviewTitle;
    private LinearLayout reviewList;
    private TextView offlineNote;
    private View syncCard;
    private TextView syncTitle;
    private TextView syncHint;
    private LinearLayout syncList;

    private Map<String, Models.PeriodAmounts> amounts;
    private List<Models.Account> accounts;
    private Models.ExchangeRates rates = new Models.ExchangeRates();
    private int pending;
    private int loadedVersion = -1;
    /** When the oldest figures shown were saved, while the server can't be reached; 0 when they are current. */
    private long offlineSince;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_overview, container, false);
        refresh = view.findViewById(R.id.refresh);
        monthExpense = view.findViewById(R.id.month_expense);
        monthIncome = view.findViewById(R.id.month_income);
        lastMonthExpense = view.findViewById(R.id.last_month_expense);
        otherCurrencies = view.findViewById(R.id.other_currencies);
        periods = view.findViewById(R.id.periods);
        netAssets = view.findViewById(R.id.net_assets);
        assetsDetail = view.findViewById(R.id.assets_detail);
        recentList = view.findViewById(R.id.recent_list);
        reviewCard = view.findViewById(R.id.review_card);
        reviewTitle = view.findViewById(R.id.review_title);
        reviewList = view.findViewById(R.id.review_list);
        offlineNote = view.findViewById(R.id.offline_note);
        syncCard = view.findViewById(R.id.sync_card);
        syncTitle = view.findViewById(R.id.sync_title);
        syncHint = view.findViewById(R.id.sync_hint);
        syncList = view.findViewById(R.id.sync_list);

        refresh.setOnRefreshListener(() -> {
            Sync.schedule(requireContext());
            load();
        });
        view.findViewById(R.id.see_all).setOnClickListener(v -> ((MainActivity) requireActivity()).showTab(R.id.nav_transactions));
        view.findViewById(R.id.assets_card).setOnClickListener(v -> ((MainActivity) requireActivity()).showTab(R.id.nav_accounts));
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadIfStale();
    }

    @Override
    public void reloadIfStale() {
        // a fragment restored while the activity is recreated (after a language change, say) has no views yet; onResume catches up
        if (getView() == null) {
            return;
        }

        if (loadedVersion != Ui.dataVersion) {
            load();
        }

        renderReview();
        renderSync();
    }

    /** Transactions saved on this phone that the server hasn't taken yet; ones that need a decision say so. */
    private void renderSync() {
        Server server = ServerStore.get(requireContext()).getActiveServer();
        List<LocalStore.Change> changes = server == null ? java.util.Collections.emptyList() : LocalStore.get(requireContext()).changes(server.id);
        syncCard.setVisibility(changes.isEmpty() ? View.GONE : View.VISIBLE);
        syncList.removeAllViews();

        if (changes.isEmpty()) {
            return;
        }

        boolean attention = false;

        for (LocalStore.Change change : changes) {
            attention |= !change.isWaiting();
        }

        syncTitle.setText(getResources().getQuantityString(R.plurals.sync_count, changes.size(), changes.size()));
        syncHint.setText(attention ? R.string.sync_hint_attention : R.string.sync_hint_waiting);
        LayoutInflater inflater = LayoutInflater.from(requireContext());

        for (LocalStore.Change change : changes) {
            View row = inflater.inflate(R.layout.item_transaction, syncList, false);
            ChangeViews.bind(row, change);
            row.setOnClickListener(v -> ChangeViews.open(requireActivity(), change, this::renderSync));
            syncList.addView(row);
        }
    }

    /** Purchases picked up from Wallet, waiting to be saved; tapping one opens it in the add screen. */
    private void renderReview() {
        Server server = ServerStore.get(requireContext()).getActiveServer();
        List<PendingPayments.Item> items = PendingPayments.list(requireContext());
        reviewCard.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        reviewList.removeAllViews();

        if (items.isEmpty()) {
            return;
        }

        reviewTitle.setText(getResources().getQuantityString(R.plurals.to_review_count, items.size(), items.size()));
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        String defaultCurrency = server == null ? null : server.getDefaultCurrency();

        for (PendingPayments.Item item : items) {
            View row = inflater.inflate(R.layout.item_transaction, reviewList, false);
            String title = item.merchant.isEmpty() ? getString(R.string.unknown_merchant) : item.merchant;
            Ui.bindAvatar(row.findViewById(R.id.avatar), title, null);
            ((TextView) row.findViewById(R.id.title)).setText(title);
            ((TextView) row.findViewById(R.id.subtitle)).setText(item.card.isEmpty() ? getString(R.string.google_wallet) : item.card);
            TextView amount = row.findViewById(R.id.amount);
            amount.setText(Formats.signedAmount(item.amount, item.currency.isEmpty() ? defaultCurrency : item.currency, !item.refund));
            amount.setTextColor(Ui.amountColor(requireContext(), item.refund ? Models.TRANSACTION_INCOME : Models.TRANSACTION_EXPENSE));
            ((TextView) row.findViewById(R.id.meta)).setText(Formats.dayHeader(requireContext(), item.time / 1000) + " " + Formats.time(requireContext(), item.time));
            row.setOnClickListener(v -> startActivity(TransactionEditActivity.reviewIntent(requireContext(), item.id)));
            row.setOnLongClickListener(v -> {
                dismiss(item);
                return true;
            });
            reviewList.addView(row);
        }
    }

    private void dismiss(PendingPayments.Item item) {
        PendingPayments.remove(requireContext(), item.id);
        SpendingWidget.refreshAll(requireContext());
        renderReview();
        Snackbar.make(refresh, R.string.purchase_dismissed, Snackbar.LENGTH_LONG).setAction(R.string.undo, v -> {
            if (isAdded()) {
                PendingPayments.restore(requireContext(), item);
                SpendingWidget.refreshAll(requireContext());
                renderReview();
            }
        }).show();
    }

    private void load() {
        Server server = ServerStore.get(requireContext()).getActiveServer();

        if (server == null) {
            return;
        }

        loadedVersion = Ui.dataVersion;
        refresh.setRefreshing(true);
        offlineSince = 0;
        pending = 4;
        Api api = new Api(server);

        // the query holds today's date, so keep the answer under one name to have figures to show on another day
        new Api(server).cacheAs("v1/transactions/amounts.json").get("v1/transactions/amounts.json", Api.query("query", Models.PeriodAmounts.query(server, PERIODS), "use_transaction_timezone", "false"), Models.PeriodAmounts::parseAll, new Api.Callback<Map<String, Models.PeriodAmounts>>() {
            @Override
            public void onSuccess(Map<String, Models.PeriodAmounts> result) {
                amounts = result;
                done();
            }

            @Override
            public void onCached(Map<String, Models.PeriodAmounts> result, long savedAt) {
                cached(savedAt);
                onSuccess(result);
            }

            @Override
            public void onError(ApiException error) {
                failed(error);
            }
        });

        api.get("v1/accounts/list.json", Api.query("visible_only", "true"), Models.Account::parseList, new Api.Callback<List<Models.Account>>() {
            @Override
            public void onSuccess(List<Models.Account> result) {
                accounts = result;
                done();
            }

            @Override
            public void onCached(List<Models.Account> result, long savedAt) {
                cached(savedAt);
                onSuccess(result);
            }

            @Override
            public void onError(ApiException error) {
                failed(error);
            }
        });

        api.get("v1/exchange_rates/latest.json", null, Models.ExchangeRates::parse, new Api.Callback<Models.ExchangeRates>() {
            @Override
            public void onSuccess(Models.ExchangeRates result) {
                rates = result;
                done();
            }

            @Override
            public void onError(ApiException error) {
                // without rates, other currencies are listed separately
                done();
            }
        });

        api.get("v1/transactions/list.json", Api.query("count", String.valueOf(RECENT_COUNT), "page", "1", "max_time", "0", "min_time", "0"), Models.TransactionPage::parse, new Api.Callback<Models.TransactionPage>() {
            @Override
            public void onSuccess(Models.TransactionPage page) {
                if (isAdded()) {
                    renderRecent(page.items);
                }

                done();
            }

            @Override
            public void onCached(Models.TransactionPage page, long savedAt) {
                cached(savedAt);
                onSuccess(page);
            }

            @Override
            public void onError(ApiException error) {
                failed(error);
            }
        });
    }

    private void done() {
        if (--pending > 0 || !isAdded()) {
            return;
        }

        refresh.setRefreshing(false);
        render();
        renderOffline();
    }

    private void renderOffline() {
        offlineNote.setVisibility(offlineSince == 0 ? View.GONE : View.VISIBLE);

        if (offlineSince > 0) {
            offlineNote.setText(getString(R.string.offline_note, Ui.savedAt(requireContext(), offlineSince)));
        } else if (offlineSince < 0) {
            offlineNote.setText(R.string.offline_note_nothing_saved);
        }
    }

    private void cached(long savedAt) {
        offlineSince = offlineSince <= 0 ? savedAt : Math.min(offlineSince, savedAt);
    }

    private void failed(ApiException error) {
        if (!isAdded()) {
            return;
        }

        if (error.unreachable) {
            // the note at the top already says the server can't be reached
            offlineSince = offlineSince == 0 ? -1 : offlineSince;
            done();
            return;
        }

        Snackbar.make(refresh, error.getMessage(), Snackbar.LENGTH_LONG).setAction(R.string.retry, v -> load()).show();
        done();
    }

    private void render() {
        Server server = ServerStore.get(requireContext()).getActiveServer();

        if (server == null) {
            return;
        }

        String currency = server.getDefaultCurrency();
        Set<String> unconverted = new LinkedHashSet<>();

        if (amounts != null) {
            long[] month = total(amounts.get("thisMonth"), currency, unconverted);
            monthExpense.setText(Formats.amount(month[1], currency));
            monthIncome.setText(Formats.amount(month[0], currency));
            lastMonthExpense.setText(Formats.amount(total(amounts.get("lastMonth"), currency, unconverted)[1], currency));

            periods.removeAllViews();
            addPeriod(R.string.today, total(amounts.get("today"), currency, unconverted), currency);
            addPeriod(R.string.this_week, total(amounts.get("thisWeek"), currency, unconverted), currency);
            addPeriod(R.string.this_year, total(amounts.get("thisYear"), currency, unconverted), currency);
        }

        if (accounts != null) {
            long assets = 0;
            long liabilities = 0;
            long net = 0;

            for (Models.Account account : Models.Account.selectable(accounts)) {
                Long converted = rates.convert(account.balance, account.currency, currency);

                if (converted == null) {
                    unconverted.add(account.currency);
                    continue;
                }

                net += converted;

                if (account.isLiability || converted < 0) {
                    liabilities += converted;
                } else {
                    assets += converted;
                }
            }

            netAssets.setText(Formats.amount(net, currency));
            assetsDetail.setText(getString(R.string.total_assets) + " " + Formats.amount(assets, currency)
                + "  ·  " + getString(R.string.total_liabilities) + " " + Formats.amount(Math.abs(liabilities), currency));
        }

        unconverted.remove(currency);
        otherCurrencies.setVisibility(unconverted.isEmpty() ? View.GONE : View.VISIBLE);
        otherCurrencies.setText(getString(R.string.other_currencies, TextUtils.join(", ", unconverted)));
    }

    /** Income and expense of a period in the default currency; currencies without a rate are left out and reported. */
    private long[] total(Models.PeriodAmounts period, String currency, Set<String> unconverted) {
        return period == null ? new long[] { 0, 0 } : period.total(currency, rates, unconverted);
    }

    private void addPeriod(int labelRes, long[] values, String currency) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_period, periods, false);
        ((TextView) row.findViewById(R.id.label)).setText(labelRes);
        ((TextView) row.findViewById(R.id.expense)).setText("−" + Formats.amount(values[1], currency));
        ((TextView) row.findViewById(R.id.income)).setText("+" + Formats.amount(values[0], currency));
        periods.addView(row);
    }

    private void renderRecent(List<Models.Transaction> transactions) {
        recentList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());

        if (transactions.isEmpty()) {
            TextView empty = new TextView(requireContext());
            int padding = getResources().getDimensionPixelSize(R.dimen.dialog_padding);
            empty.setPadding(padding, padding, padding, padding);
            empty.setText(R.string.no_transactions_hint);
            recentList.addView(empty);
            return;
        }

        for (Models.Transaction transaction : transactions) {
            View row = inflater.inflate(R.layout.item_transaction, recentList, false);
            Ui.bindTransaction(row, transaction);
            ((TextView) row.findViewById(R.id.meta)).setText(Formats.dayHeader(requireContext(), transaction.time));
            row.setOnClickListener(v -> startActivity(TransactionEditActivity.editIntent(requireContext(), transaction.id)));
            recentList.addView(row);
        }
    }
}
