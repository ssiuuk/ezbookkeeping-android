package com.ssiuuk.ezbookkeeping;

import android.os.Bundle;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

/** The transactions of one account. */
public class AccountTransactionsActivity extends BaseActivity {
    static final String EXTRA_ACCOUNT_ID = "account_id";
    static final String EXTRA_ACCOUNT_NAME = "account_name";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fragment);
        setupToolbar(0, true);
        setTitle(getIntent().getStringExtra(EXTRA_ACCOUNT_NAME));

        String accountIds = getIntent().getStringExtra(EXTRA_ACCOUNT_ID);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, TransactionsFragment.forAccount(accountIds))
                .commit();
        }

        ExtendedFloatingActionButton fab = findViewById(R.id.fab);
        padForSystemBars(findViewById(R.id.snackbar_anchor), false, true);
        fab.setOnClickListener(view -> startActivity(TransactionEditActivity.addIntent(this, accountIds == null ? null : accountIds.split(",")[0])));
    }
}
