package com.ssiuuk.ezbookkeeping;

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.ssiuuk.ezbookkeeping.data.Api;
import com.ssiuuk.ezbookkeeping.data.ApiException;
import com.ssiuuk.ezbookkeeping.data.Models;
import com.ssiuuk.ezbookkeeping.data.Server;
import com.ssiuuk.ezbookkeeping.data.ServerStore;

import java.util.ArrayList;
import java.util.List;

/** Chooses what a home screen widget shows: the period, the book and whether amounts are hidden. */
public class SpendingWidgetConfigureActivity extends BaseActivity {
    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private SpendingWidget.Settings settings;
    private LinearLayout container;
    private List<Models.Book> books = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle extras = getIntent().getExtras();

        if (extras != null) {
            widgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        }

        // leaving without saving doesn't add the widget
        setResult(RESULT_CANCELED, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }

        setContentView(R.layout.activity_widget_configure);
        setupToolbar(R.string.widget_settings, false);
        padForSystemBars(findViewById(R.id.scroll), false, true);
        container = findViewById(R.id.container);
        settings = SpendingWidget.Settings.load(this, widgetId);
        findViewById(R.id.save).setOnClickListener(view -> save());
        render();
        loadBooks();
    }

    private void loadBooks() {
        Server server = ServerStore.get(this).getActiveServer();

        if (server == null || !server.isSignedIn()) {
            return;
        }

        new Api(server).withoutBook().get("v1/books/list.json", null, Models.Book::parseList, new Api.Callback<List<Models.Book>>() {
            @Override
            public void onSuccess(List<Models.Book> result) {
                books = result;
                render();
            }

            @Override
            public void onError(ApiException error) {
                // servers without family books only have the user's own book
            }
        });
    }

    private void render() {
        container.removeAllViews();
        addItem(R.drawable.ic_event, getString(R.string.widget_period), SpendingWidget.periodName(this, settings.period), view -> choosePeriod());

        if (books.size() > 1) {
            addItem(R.drawable.ic_book, getString(R.string.book), bookName(), view -> chooseBook());
        }

        addItem(R.drawable.ic_visibility_off, getString(R.string.widget_hide_amounts),
            getString(settings.hideAmounts ? R.string.widget_amounts_hidden : R.string.widget_amounts_shown), view -> {
                settings.hideAmounts = !settings.hideAmounts;
                render();
            });
    }

    private void choosePeriod() {
        String[] names = new String[SpendingWidget.PERIODS.length];
        int checked = 0;

        for (int i = 0; i < names.length; i++) {
            names[i] = SpendingWidget.periodName(this, SpendingWidget.PERIODS[i]);

            if (SpendingWidget.PERIODS[i].equals(settings.period)) {
                checked = i;
            }
        }

        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.widget_period)
            .setSingleChoiceItems(names, checked, (dialog, which) -> {
                settings.period = SpendingWidget.PERIODS[which];
                dialog.dismiss();
                render();
            })
            .show();
    }

    /** The app's book, or a fixed one: the user's own or a shared book. */
    private void chooseBook() {
        String[] names = new String[books.size() + 1];
        names[0] = getString(R.string.widget_book_app);
        int checked = 0;

        for (int i = 0; i < books.size(); i++) {
            Models.Book book = books.get(i);
            names[i + 1] = bookName(book);

            if (book.isPersonal ? SpendingWidget.BOOK_PERSONAL.equals(settings.book) : book.id.equals(settings.book)) {
                checked = i + 1;
            }
        }

        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_book)
            .setSingleChoiceItems(names, checked, (dialog, which) -> {
                if (which == 0) {
                    settings.book = "";
                    settings.bookName = "";
                } else {
                    Models.Book book = books.get(which - 1);
                    settings.book = book.isPersonal ? SpendingWidget.BOOK_PERSONAL : book.id;
                    settings.bookName = book.isPersonal ? "" : book.name;
                }

                dialog.dismiss();
                render();
            })
            .show();
    }

    private String bookName() {
        if (settings.book.isEmpty()) {
            return getString(R.string.widget_book_app);
        }

        return SpendingWidget.BOOK_PERSONAL.equals(settings.book) ? getString(R.string.my_book) : settings.bookName;
    }

    private String bookName(Models.Book book) {
        if (book.isPersonal) {
            return getString(R.string.my_book);
        }

        return book.isOwner ? book.name : book.name + " · " + getString(R.string.book_owner, book.ownerNickname);
    }

    private void save() {
        settings.save(this, widgetId);
        SpendingWidget.refresh(this, new int[] { widgetId }, () -> { });
        setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
        finish();
    }

    private void addItem(int iconRes, String title, String subtitle, View.OnClickListener listener) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_more, container, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView subtitleView = row.findViewById(R.id.subtitle);
        subtitleView.setText(subtitle);
        subtitleView.setVisibility(View.VISIBLE);
        row.setOnClickListener(listener);
        container.addView(row);
    }
}
