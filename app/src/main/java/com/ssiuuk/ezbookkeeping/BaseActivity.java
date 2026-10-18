package com.ssiuuk.ezbookkeeping;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.snackbar.Snackbar;

/** Draws every screen edge to edge and gives it the shared toolbar behaviour. */
public abstract class BaseActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
    }

    protected MaterialToolbar setupToolbar(int titleRes, boolean showBack) {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        if (titleRes != 0) {
            toolbar.setTitle(titleRes);
        }

        if (showBack && getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            toolbar.setNavigationOnClickListener(view -> getOnBackPressedDispatcher().onBackPressed());
        }

        return toolbar;
    }

    protected void showMessage(CharSequence message) {
        View root = findViewById(R.id.snackbar_anchor);

        if (root == null) {
            root = findViewById(android.R.id.content);
        }

        Snackbar.make(root, message, Snackbar.LENGTH_LONG).show();
    }

    /** Keeps scrolling content clear of the navigation bar and keyboard while it scrolls behind them. */
    public static void padForSystemBars(View view, boolean top, boolean bottom) {
        final int left = view.getPaddingLeft();
        final int startTop = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int startBottom = view.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(left + insets.left, startTop + (top ? insets.top : 0), right + insets.right, startBottom + (bottom ? insets.bottom : 0));
            return windowInsets;
        });

        if (view instanceof ViewGroup) {
            ((ViewGroup) view).setClipToPadding(false);
        }

        ViewCompat.requestApplyInsets(view);
    }
}
