package com.ssiuuk.ezbookkeeping;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.ssiuuk.ezbookkeeping.data.Api;
import com.ssiuuk.ezbookkeeping.data.ApiException;
import com.ssiuuk.ezbookkeeping.data.Formats;
import com.ssiuuk.ezbookkeeping.data.Models;
import com.ssiuuk.ezbookkeeping.data.Server;
import com.ssiuuk.ezbookkeeping.data.ServerStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

/** Adds a new expense, income or transfer, or edits an existing one. */
public class TransactionEditActivity extends BaseActivity {
    private static final String EXTRA_TRANSACTION_ID = "transaction_id";
    private static final String EXTRA_ACCOUNT_ID = "account_id";

    private Server server;
    private String transactionId;
    private Models.Transaction original;

    private List<Models.Account> accounts = new ArrayList<>();
    private Object categoriesResult;
    private List<Models.Tag> tags = new ArrayList<>();

    private int type = Models.TRANSACTION_EXPENSE;
    private Models.Category category;
    private Models.Account account;
    private Models.Account destinationAccount;
    private final List<String> tagIds = new ArrayList<>();
    private final Calendar time = Calendar.getInstance();
    private final String clientSessionId = UUID.randomUUID().toString();

    private MaterialButtonToggleGroup typeGroup;
    private TextInputLayout amountLayout;
    private TextInputEditText amountInput;
    private TextInputLayout categoryLayout;
    private TextInputEditText categoryInput;
    private TextInputLayout accountLayout;
    private TextInputEditText accountInput;
    private TextInputLayout destinationAccountLayout;
    private TextInputEditText destinationAccountInput;
    private TextInputLayout destinationAmountLayout;
    private TextInputEditText destinationAmountInput;
    private TextInputEditText dateInput;
    private TextInputEditText timeInput;
    private TextInputEditText tagsInput;
    private TextInputEditText commentInput;
    private MaterialButton saveButton;
    private View progress;
    private int pendingLoads;
    private boolean saving;

    public static Intent addIntent(Context context, String accountId) {
        return new Intent(context, TransactionEditActivity.class).putExtra(EXTRA_ACCOUNT_ID, accountId);
    }

    public static Intent editIntent(Context context, String transactionId) {
        return new Intent(context, TransactionEditActivity.class).putExtra(EXTRA_TRANSACTION_ID, transactionId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        server = ServerStore.get(this).getActiveServer();

        if (server == null || !server.isSignedIn()) {
            finish();
            return;
        }

        transactionId = getIntent().getStringExtra(EXTRA_TRANSACTION_ID);
        setContentView(R.layout.activity_transaction_edit);
        setupToolbar(transactionId == null ? R.string.add_transaction : R.string.edit_transaction, true).setNavigationIcon(R.drawable.ic_close);
        padForSystemBars(findViewById(R.id.scroll), false, true);

        typeGroup = findViewById(R.id.type_group);
        amountLayout = findViewById(R.id.amount_layout);
        amountInput = findViewById(R.id.amount);
        categoryLayout = findViewById(R.id.category_layout);
        categoryInput = findViewById(R.id.category);
        accountLayout = findViewById(R.id.account_layout);
        accountInput = findViewById(R.id.account);
        destinationAccountLayout = findViewById(R.id.destination_account_layout);
        destinationAccountInput = findViewById(R.id.destination_account);
        destinationAmountLayout = findViewById(R.id.destination_amount_layout);
        destinationAmountInput = findViewById(R.id.destination_amount);
        dateInput = findViewById(R.id.date);
        timeInput = findViewById(R.id.time);
        tagsInput = findViewById(R.id.tags);
        commentInput = findViewById(R.id.comment);
        saveButton = findViewById(R.id.save_button);
        progress = findViewById(R.id.progress);

        typeGroup.check(R.id.type_expense);
        typeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }

            int newType = checkedId == R.id.type_income ? Models.TRANSACTION_INCOME
                : checkedId == R.id.type_transfer ? Models.TRANSACTION_TRANSFER
                : Models.TRANSACTION_EXPENSE;

            if (newType != type) {
                type = newType;
                category = null;
                renderAll();
            }
        });

        categoryInput.setOnClickListener(view -> chooseCategory());
        categoryLayout.setEndIconOnClickListener(view -> chooseCategory());
        accountInput.setOnClickListener(view -> chooseAccount(false));
        accountLayout.setEndIconOnClickListener(view -> chooseAccount(false));
        destinationAccountInput.setOnClickListener(view -> chooseAccount(true));
        destinationAccountLayout.setEndIconOnClickListener(view -> chooseAccount(true));
        dateInput.setOnClickListener(view -> chooseDate());
        timeInput.setOnClickListener(view -> chooseTime());
        tagsInput.setOnClickListener(view -> chooseTags());
        findViewById(R.id.tags_layout).setOnClickListener(view -> chooseTags());
        ((TextInputLayout) findViewById(R.id.tags_layout)).setEndIconOnClickListener(view -> chooseTags());
        saveButton.setOnClickListener(view -> save());

        amountInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                amountLayout.setError(null);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        load();

        if (transactionId == null) {
            amountInput.requestFocus();
            amountInput.postDelayed(() -> {
                InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);

                if (keyboard != null) {
                    keyboard.showSoftInput(amountInput, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 300);
        }

        renderAll();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (transactionId != null) {
            MenuItem delete = menu.add(0, 1, 0, R.string.delete);
            delete.setIcon(R.drawable.ic_delete);
            delete.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 1) {
            confirmDelete();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void load() {
        Api api = new Api(server);
        setBusy(true);
        pendingLoads = transactionId == null ? 3 : 4;

        api.get("v1/accounts/list.json", Api.query("visible_only", "true"), Models.Account::parseList, new Api.Callback<List<Models.Account>>() {
            @Override
            public void onSuccess(List<Models.Account> result) {
                accounts = Models.Account.selectable(result);
                loaded();
            }

            @Override
            public void onError(ApiException error) {
                loadFailed(error);
            }
        });

        api.get("v1/transaction/categories/list.json", null, result -> result, new Api.Callback<Object>() {
            @Override
            public void onSuccess(Object result) {
                categoriesResult = result;
                loaded();
            }

            @Override
            public void onError(ApiException error) {
                loadFailed(error);
            }
        });

        api.get("v1/transaction/tags/list.json", null, Models.Tag::parseList, new Api.Callback<List<Models.Tag>>() {
            @Override
            public void onSuccess(List<Models.Tag> result) {
                tags = result;
                loaded();
            }

            @Override
            public void onError(ApiException error) {
                // tags are optional
                loaded();
            }
        });

        if (transactionId != null) {
            api.get("v1/transactions/get.json", Api.query("id", transactionId, "trim_account", "false", "trim_category", "false", "trim_tag", "false"),
                result -> Models.Transaction.parse((JSONObject) result), new Api.Callback<Models.Transaction>() {
                    @Override
                    public void onSuccess(Models.Transaction result) {
                        original = result;
                        loaded();
                    }

                    @Override
                    public void onError(ApiException error) {
                        loadFailed(error);
                    }
                });
        }
    }

    private void loaded() {
        if (--pendingLoads > 0) {
            return;
        }

        setBusy(false);

        if (original != null) {
            fillFromOriginal();
        } else {
            String accountId = getIntent().getStringExtra(EXTRA_ACCOUNT_ID);

            if (accountId == null) {
                accountId = server.getUser().optString("defaultAccountId", "0");
            }

            account = findAccount(accountId);

            if (account == null && !accounts.isEmpty()) {
                account = accounts.get(0);
            }
        }

        renderAll();
    }

    private void loadFailed(ApiException error) {
        pendingLoads = Integer.MAX_VALUE;
        setBusy(false);
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.something_went_wrong)
            .setMessage(error.getMessage())
            .setPositiveButton(R.string.retry, (dialog, which) -> load())
            .setNegativeButton(R.string.cancel, (dialog, which) -> finish())
            .setCancelable(false)
            .show();
    }

    private void fillFromOriginal() {
        if (original.type == Models.TRANSACTION_MODIFY_BALANCE || !original.editable) {
            // balance changes and read-only transactions are handled by the full web page
            startActivity(WebActivity.intent(this, "/transaction/detail?id=" + original.id + "&type=" + original.type));
            finish();
            return;
        }

        type = original.type;
        typeGroup.check(type == Models.TRANSACTION_INCOME ? R.id.type_income : type == Models.TRANSACTION_TRANSFER ? R.id.type_transfer : R.id.type_expense);
        amountInput.setText(Formats.editableAmount(original.sourceAmount));
        account = findAccount(original.sourceAccountId);
        destinationAccount = findAccount(original.destinationAccountId);
        category = findCategory(original.categoryId);
        tagIds.clear();
        tagIds.addAll(original.tagIds);
        time.setTimeInMillis(original.time * 1000);
        commentInput.setText(original.comment);

        if (type == Models.TRANSACTION_TRANSFER) {
            destinationAmountInput.setText(Formats.editableAmount(original.destinationAmount));
        }
    }

    private void renderAll() {
        boolean transfer = type == Models.TRANSACTION_TRANSFER;
        categoryInput.setText(category == null ? "" : categoryLabel(category));
        accountLayout.setHint(transfer ? R.string.from_account : R.string.account);
        accountInput.setText(account == null ? "" : account.name);
        destinationAccountLayout.setVisibility(transfer ? View.VISIBLE : View.GONE);
        destinationAccountInput.setText(destinationAccount == null ? "" : destinationAccount.name);

        boolean differentCurrencies = transfer && account != null && destinationAccount != null && !TextUtils.equals(account.currency, destinationAccount.currency);
        destinationAmountLayout.setVisibility(differentCurrencies ? View.VISIBLE : View.GONE);
        destinationAmountLayout.setSuffixText(destinationAccount == null ? null : destinationAccount.currency);
        amountLayout.setSuffixText(account == null ? null : account.currency);

        dateInput.setText(Formats.date(this, time.getTimeInMillis()));
        timeInput.setText(Formats.time(this, time.getTimeInMillis()));

        List<String> names = new ArrayList<>();

        for (Models.Tag tag : tags) {
            if (tagIds.contains(tag.id)) {
                names.add(tag.name);
            }
        }

        tagsInput.setText(TextUtils.join(", ", names));
    }

    private String categoryLabel(Models.Category category) {
        Models.Category parent = findParentCategory(category.id);
        return parent == null ? category.name : parent.name + " › " + category.name;
    }

    private List<Models.Category> categoriesOfType() {
        return Models.Category.parseByType(categoriesResult, Models.categoryTypeOf(type));
    }

    private Models.Category findCategory(String id) {
        for (int categoryType = Models.CATEGORY_INCOME; categoryType <= Models.CATEGORY_TRANSFER; categoryType++) {
            for (Models.Category parent : Models.Category.parseByType(categoriesResult, categoryType)) {
                for (Models.Category sub : parent.subCategories) {
                    if (sub.id.equals(id)) {
                        return sub;
                    }
                }
            }
        }

        return null;
    }

    private Models.Category findParentCategory(String subId) {
        for (int categoryType = Models.CATEGORY_INCOME; categoryType <= Models.CATEGORY_TRANSFER; categoryType++) {
            for (Models.Category parent : Models.Category.parseByType(categoriesResult, categoryType)) {
                for (Models.Category sub : parent.subCategories) {
                    if (sub.id.equals(subId)) {
                        return parent;
                    }
                }
            }
        }

        return null;
    }

    private Models.Account findAccount(String id) {
        if (id == null) {
            return null;
        }

        for (Models.Account candidate : accounts) {
            if (candidate.id.equals(id)) {
                return candidate;
            }
        }

        return null;
    }

    private void chooseCategory() {
        List<ChoiceSheet.Choice> choices = new ArrayList<>();

        for (Models.Category parent : categoriesOfType()) {
            List<ChoiceSheet.Choice> subs = new ArrayList<>();

            for (Models.Category sub : parent.subCategories) {
                if (sub.hidden) {
                    continue;
                }

                ChoiceSheet.Choice choice = ChoiceSheet.Choice.item(sub.id, sub.name, null, TextUtils.isEmpty(sub.color) ? parent.color : sub.color);
                choice.selected = category != null && category.id.equals(sub.id);
                subs.add(choice);
            }

            if (!subs.isEmpty()) {
                choices.add(ChoiceSheet.Choice.header(parent.name));
                choices.addAll(subs);
            }
        }

        if (choices.isEmpty()) {
            showMessage(getString(R.string.no_categories));
            return;
        }

        ChoiceSheet.show(this, getString(R.string.choose_category), choices, choice -> {
            category = findCategory(choice.id);
            categoryLayout.setError(null);
            renderAll();
        });
    }

    private void chooseAccount(boolean destination) {
        List<ChoiceSheet.Choice> choices = new ArrayList<>();
        Models.Account current = destination ? destinationAccount : account;

        for (Models.Account candidate : accounts) {
            ChoiceSheet.Choice choice = ChoiceSheet.Choice.item(candidate.id, candidate.name, Formats.amount(candidate.balance, candidate.currency), candidate.color);
            choice.selected = current != null && current.id.equals(candidate.id);
            choices.add(choice);
        }

        if (choices.isEmpty()) {
            showMessage(getString(R.string.no_accounts_hint));
            return;
        }

        ChoiceSheet.show(this, getString(R.string.choose_account), choices, choice -> {
            if (destination) {
                destinationAccount = findAccount(choice.id);
                destinationAccountLayout.setError(null);
            } else {
                account = findAccount(choice.id);
                accountLayout.setError(null);
            }

            renderAll();
        });
    }

    private void chooseTags() {
        List<Models.Tag> visible = new ArrayList<>();

        for (Models.Tag tag : tags) {
            if (!tag.hidden || tagIds.contains(tag.id)) {
                visible.add(tag);
            }
        }

        if (visible.isEmpty()) {
            showMessage(getString(R.string.no_tags));
            return;
        }

        String[] names = new String[visible.size()];
        boolean[] checked = new boolean[visible.size()];

        for (int i = 0; i < visible.size(); i++) {
            names[i] = visible.get(i).name;
            checked[i] = tagIds.contains(visible.get(i).id);
        }

        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_tags)
            .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok, (dialog, which) -> {
                tagIds.clear();

                for (int i = 0; i < visible.size(); i++) {
                    if (checked[i]) {
                        tagIds.add(visible.get(i).id);
                    }
                }

                renderAll();
            })
            .show();
    }

    private void chooseDate() {
        // the date picker works in UTC, so hand it the local calendar day at UTC midnight
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(time.get(Calendar.YEAR), time.get(Calendar.MONTH), time.get(Calendar.DAY_OF_MONTH));

        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.date)
            .setSelection(utc.getTimeInMillis())
            .build();
        picker.addOnPositiveButtonClickListener(selection -> {
            Calendar picked = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            picked.setTimeInMillis(selection);
            time.set(picked.get(Calendar.YEAR), picked.get(Calendar.MONTH), picked.get(Calendar.DAY_OF_MONTH));
            renderAll();
        });
        picker.show(getSupportFragmentManager(), "date");
    }

    private void chooseTime() {
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
            .setTimeFormat(DateFormat.is24HourFormat(this) ? TimeFormat.CLOCK_24H : TimeFormat.CLOCK_12H)
            .setHour(time.get(Calendar.HOUR_OF_DAY))
            .setMinute(time.get(Calendar.MINUTE))
            .setTitleText(R.string.time)
            .build();
        picker.addOnPositiveButtonClickListener(view -> {
            time.set(Calendar.HOUR_OF_DAY, picker.getHour());
            time.set(Calendar.MINUTE, picker.getMinute());
            renderAll();
        });
        picker.show(getSupportFragmentManager(), "time");
    }

    private void save() {
        if (saving) {
            return;
        }

        Long amount = Formats.parseAmount(amountInput.getText() == null ? "" : amountInput.getText().toString());
        boolean valid = true;

        if (amount == null || amount < 0) {
            amountLayout.setError(getString(R.string.amount_required));
            valid = false;
        }

        if (category == null) {
            categoryLayout.setError(getString(R.string.category_required));
            valid = false;
        }

        if (account == null) {
            accountLayout.setError(getString(R.string.account_required));
            valid = false;
        }

        Long destinationAmount = amount;

        if (type == Models.TRANSACTION_TRANSFER) {
            if (destinationAccount == null) {
                destinationAccountLayout.setError(getString(R.string.destination_account_required));
                valid = false;
            } else if (account != null && destinationAccount.id.equals(account.id)) {
                destinationAccountLayout.setError(getString(R.string.same_account));
                valid = false;
            }

            if (destinationAmountLayout.getVisibility() == View.VISIBLE) {
                destinationAmount = Formats.parseAmount(destinationAmountInput.getText() == null ? "" : destinationAmountInput.getText().toString());

                if (destinationAmount == null || destinationAmount < 0) {
                    destinationAmountLayout.setError(getString(R.string.amount_required));
                    valid = false;
                }
            }
        }

        if (!valid) {
            return;
        }

        JSONObject body = new JSONObject();

        try {
            if (original != null) {
                body.put("id", original.id);
            } else {
                body.put("clientSessionId", clientSessionId);
            }

            body.put("type", type);
            body.put("categoryId", category.id);
            body.put("time", time.getTimeInMillis() / 1000);
            body.put("utcOffset", TimeZone.getDefault().getOffset(time.getTimeInMillis()) / 60000);
            body.put("sourceAccountId", account.id);
            body.put("destinationAccountId", type == Models.TRANSACTION_TRANSFER ? destinationAccount.id : "0");
            body.put("sourceAmount", amount);
            body.put("destinationAmount", type == Models.TRANSACTION_TRANSFER ? destinationAmount : 0);
            body.put("hideAmount", original != null && original.hideAmount);
            body.put("tagIds", new JSONArray(tagIds));
            body.put("pictureIds", new JSONArray(original == null ? new ArrayList<String>() : original.pictureIds));
            body.put("projectId", original == null ? "0" : original.projectId);
            body.put("comment", commentInput.getText() == null ? "" : commentInput.getText().toString().trim());

            if (original != null && original.geoLocation != null) {
                body.put("geoLocation", original.geoLocation);
            }
        } catch (JSONException ex) {
            return;
        }

        saving = true;
        setBusy(true);
        new Api(server).post(original == null ? "v1/transactions/add.json" : "v1/transactions/modify.json", body, null, new Api.Callback<Object>() {
            @Override
            public void onSuccess(Object result) {
                Ui.dataChanged();
                finish();
            }

            @Override
            public void onError(ApiException error) {
                saving = false;
                setBusy(false);
                showMessage(error.getMessage());
            }
        });
    }

    private void confirmDelete() {
        if (original == null) {
            return;
        }

        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_transaction_title)
            .setMessage(R.string.delete_transaction_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete, (dialog, which) -> {
                JSONObject body = new JSONObject();

                try {
                    body.put("id", original.id);
                } catch (JSONException ex) {
                    return;
                }

                setBusy(true);
                new Api(server).post("v1/transactions/delete.json", body, null, new Api.Callback<Object>() {
                    @Override
                    public void onSuccess(Object result) {
                        Ui.dataChanged();
                        finish();
                    }

                    @Override
                    public void onError(ApiException error) {
                        setBusy(false);
                        showMessage(error.getMessage());
                    }
                });
            })
            .show();
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        saveButton.setEnabled(!busy);
    }
}
