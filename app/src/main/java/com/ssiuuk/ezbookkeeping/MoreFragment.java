package com.ssiuuk.ezbookkeeping;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

/** Everything that isn't a daily screen: reports, budgets and other pages, plus settings. */
public class MoreFragment extends Fragment {
    private LinearLayout container;
    private boolean renderedWithBooks;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle savedInstanceState) {
        NestedScrollView scroll = new NestedScrollView(requireContext());
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, getResources().getDimensionPixelSize(R.dimen.dialog_padding));
        container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(container);
        render();
        return scroll;
    }

    @Override
    public void onResume() {
        super.onResume();

        if (renderedWithBooks != hasBooks()) {
            render();
        }
    }

    private boolean hasBooks() {
        return getActivity() instanceof MainActivity && ((MainActivity) getActivity()).serverHasBooks();
    }

    private void render() {
        container.removeAllViews();
        renderedWithBooks = hasBooks();

        addHeader(R.string.section_insights);
        addWebItem(R.drawable.ic_bar_chart, R.string.reports, "/statistic/transaction");

        if (renderedWithBooks) {
            // these pages only exist on servers with the family books features
            addWebItem(R.drawable.ic_pie_chart, R.string.budgets, "/budgets");
            addWebItem(R.drawable.ic_household, R.string.household_report, "/reports/household");
            addWebItem(R.drawable.ic_folder, R.string.projects, "/projects");
        }

        addHeader(R.string.section_organize);
        addWebItem(R.drawable.ic_category, R.string.categories, "/category/all");
        addWebItem(R.drawable.ic_label, R.string.tag_list, "/tag/list");
        addWebItem(R.drawable.ic_notes, R.string.templates, "/template/list");
        addWebItem(R.drawable.ic_schedule, R.string.scheduled_transactions, "/schedule/list");

        if (renderedWithBooks) {
            addWebItem(R.drawable.ic_group, R.string.family_books, "/user/books");
        }

        addWebItem(R.drawable.ic_swap, R.string.exchange_rates, "/exchange_rates");

        addHeader(R.string.section_app);
        addItem(R.drawable.ic_settings, getString(R.string.settings), null, view -> startActivity(new Intent(requireContext(), SettingsActivity.class)));
        addWebItem(R.drawable.ic_person, R.string.web_settings, "/settings");
        addWebItem(R.drawable.ic_web, R.string.open_web_version, "/");
    }

    private void addHeader(int titleRes) {
        View header = LayoutInflater.from(requireContext()).inflate(R.layout.item_header, container, false);
        ((TextView) header.findViewById(R.id.title)).setText(titleRes);
        container.addView(header);
    }

    private void addWebItem(int iconRes, int titleRes, String route) {
        addItem(iconRes, getString(titleRes), null, view -> startActivity(WebActivity.intent(requireContext(), route)));
    }

    private void addItem(int iconRes, String title, String subtitle, View.OnClickListener listener) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_more, container, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);

        if (subtitle != null) {
            TextView subtitleView = row.findViewById(R.id.subtitle);
            subtitleView.setText(subtitle);
            subtitleView.setVisibility(View.VISIBLE);
        }

        row.setOnClickListener(listener);
        container.addView(row);
    }
}
