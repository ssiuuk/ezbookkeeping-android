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
import com.ssiuuk.ezbookkeeping.data.Models;
import com.ssiuuk.ezbookkeeping.data.PendingPayments;
import com.ssiuuk.ezbookkeeping.data.Server;
import com.ssiuuk.ezbookkeeping.data.ServerStore;

import java.util.ArrayList;
import java.util.Calendar;
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

    private Map<String, Models.PeriodAmounts> amounts;
    private List<Models.Account> accounts;
    private Models.ExchangeRates rates = new Models.ExchangeRates();
    private int pending;
    private int loadedVersion = -1;

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

        refresh.setOnRefreshListener(this::load);
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
        if (loadedVersion != Ui.dataVersion && isAdded()) {
            load();
        }

        if (isAdded()) {
            renderReview();
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
        renderReview();
        Snackbar.make(refresh, R.string.purchase_dismissed, Snackbar.LENGTH_LONG).setAction(R.string.undo, v -> {
            if (isAdded()) {
                PendingPayments.restore(requireContext(), item);
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
        pending = 4;
        Api api = new Api(server);

        api.get("v1/transactions/amounts.json", Api.query("query", periodQuery(server), "use_transaction_timezone", "false"), Models.PeriodAmounts::parseAll, new Api.Callback<Map<String, Models.PeriodAmounts>>() {
            @Override
            public void onSuccess(Map<String, Models.PeriodAmounts> result) {
                amounts = result;
                done();
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
    }

    private void failed(ApiException error) {
        if (!isAdded()) {
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
        long income = 0;
        long expense = 0;

        if (period == null) {
            return new long[] { 0, 0 };
        }

        for (int i = 0; i < period.currencies.size(); i++) {
            String from = period.currencies.get(i);
            Long convertedIncome = rates.convert(period.incomes.get(i), from, currency);
            Long convertedExpense = rates.convert(period.expenses.get(i), from, currency);

            if (convertedIncome == null || convertedExpense == null) {
                unconverted.add(from);
                continue;
            }

            income += convertedIncome;
            expense += convertedExpense;
        }

        return new long[] { income, expense };
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

    /** Builds "today_start_end|thisWeek_start_end|…" in the phone's time zone. */
    private static String periodQuery(Server server) {
        int firstDayOfWeek = server.getUser().optInt("firstDayOfWeek", 0);
        List<String> items = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (String name : PERIODS) {
            Calendar start = Calendar.getInstance();
            start.setTimeInMillis(Formats.startOfDay(now));
            Calendar end = (Calendar) start.clone();

            switch (name) {
                case "today":
                    end.add(Calendar.DAY_OF_MONTH, 1);
                    break;
                case "thisWeek":
                    int offset = (start.get(Calendar.DAY_OF_WEEK) - 1 - firstDayOfWeek + 7) % 7;
                    start.add(Calendar.DAY_OF_MONTH, -offset);
                    end = (Calendar) start.clone();
                    end.add(Calendar.DAY_OF_MONTH, 7);
                    break;
                case "thisMonth":
                    start.set(Calendar.DAY_OF_MONTH, 1);
                    end = (Calendar) start.clone();
                    end.add(Calendar.MONTH, 1);
                    break;
                case "lastMonth":
                    start.set(Calendar.DAY_OF_MONTH, 1);
                    end = (Calendar) start.clone();
                    start.add(Calendar.MONTH, -1);
                    break;
                default:
                    start.set(Calendar.DAY_OF_YEAR, 1);
                    end = (Calendar) start.clone();
                    end.add(Calendar.YEAR, 1);
                    break;
            }

            items.add(name + "_" + start.getTimeInMillis() / 1000 + "_" + (end.getTimeInMillis() / 1000 - 1));
        }

        return TextUtils.join("|", items);
    }
}
