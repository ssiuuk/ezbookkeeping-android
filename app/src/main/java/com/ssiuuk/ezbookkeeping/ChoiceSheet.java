package com.ssiuuk.ezbookkeeping;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

/** A bottom sheet with a list of choices, optionally grouped under headers. */
final class ChoiceSheet {
    interface OnChoice {
        void onChoice(Choice choice);
    }

    static final class Choice {
        final String id;
        final String title;
        final String value;
        final String color;
        final boolean header;
        boolean selected;
        /** Shown in the circle instead of the title's first letter, such as + for an add action. */
        String symbol;

        private Choice(String id, String title, String value, String color, boolean header) {
            this.id = id;
            this.title = title;
            this.value = value;
            this.color = color;
            this.header = header;
        }

        static Choice item(String id, String title, String value, String color) {
            return new Choice(id, title, value, color, false);
        }

        static Choice symbol(String id, String title, String symbol) {
            Choice choice = new Choice(id, title, null, null, false);
            choice.symbol = symbol;
            return choice;
        }

        static Choice header(String title) {
            return new Choice(null, title, null, null, true);
        }
    }

    private ChoiceSheet() {
    }

    static void show(Context context, String title, List<Choice> choices, OnChoice onChoice) {
        BottomSheetDialog dialog = new BottomSheetDialog(context);
        LayoutInflater inflater = LayoutInflater.from(context);

        NestedScrollView scroll = new NestedScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        int padding = context.getResources().getDimensionPixelSize(R.dimen.dialog_padding);
        list.setPadding(0, 0, 0, padding);
        scroll.addView(list);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        titleView.setPadding(padding, padding / 2, padding, padding / 2);
        list.addView(titleView);

        List<Choice> items = new ArrayList<>(choices);

        for (Choice choice : items) {
            if (choice.header) {
                View header = inflater.inflate(R.layout.item_header, list, false);
                header.setPadding(padding, header.getPaddingTop(), padding, header.getPaddingBottom());
                ((TextView) header.findViewById(R.id.title)).setText(choice.title);
                ((TextView) header.findViewById(R.id.value)).setText("");
                list.addView(header);
                continue;
            }

            View row = inflater.inflate(R.layout.item_choice, list, false);
            Ui.bindAvatar(row.findViewById(R.id.avatar), choice.symbol != null ? choice.symbol : choice.title, choice.color);
            ((TextView) row.findViewById(R.id.title)).setText(choice.title);
            ((TextView) row.findViewById(R.id.value)).setText(choice.value == null ? "" : choice.value);
            row.findViewById(R.id.check).setVisibility(choice.selected ? View.VISIBLE : View.GONE);
            row.setOnClickListener(view -> {
                dialog.dismiss();
                onChoice.onChoice(choice);
            });
            list.addView(row);
        }

        dialog.setContentView(scroll);
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.show();
    }
}
