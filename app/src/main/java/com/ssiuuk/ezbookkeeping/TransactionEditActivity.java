package com.ssiuuk.ezbookkeeping;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.ssiuuk.ezbookkeeping.data.AmountExpression;
import com.ssiuuk.ezbookkeeping.data.Api;
import com.ssiuuk.ezbookkeeping.data.ApiException;
import com.ssiuuk.ezbookkeeping.data.Formats;
import com.ssiuuk.ezbookkeeping.data.LocalStore;
import com.ssiuuk.ezbookkeeping.data.Models;
import com.ssiuuk.ezbookkeeping.data.PendingPayments;
import com.ssiuuk.ezbookkeeping.data.RecentChoices;
import com.ssiuuk.ezbookkeeping.data.Server;
import com.ssiuuk.ezbookkeeping.data.ServerStore;
import com.ssiuuk.ezbookkeeping.data.Sync;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Adds a new expense, income or transfer, or edits an existing one. */
public class TransactionEditActivity extends BaseActivity {
    private static final String EXTRA_TRANSACTION_ID = "transaction_id";
    private static final String EXTRA_ACCOUNT_ID = "account_id";
    private static final String EXTRA_PENDING_ID = "pending_id";
    private static final String EXTRA_TYPE = "type";
    private static final String EXTRA_CHANGE_ID = "change_id";
    private static final String EXTRA_BOOK_ID = "book_id";
    private static final int MENU_DELETE = 1;
    private static final int MENU_DISMISS = 2;
    private static final int MAX_PHOTO_SIZE = 2048;
    private static final int MAX_MERCHANT_LENGTH = 64;
    private static final int MAX_CATEGORY_NAME_LENGTH = 64;
    // the server's first category icon and a neutral grey, for a group added from the app
    private static final String NEW_CATEGORY_ICON = "1";
    private static final String NEW_CATEGORY_COLOR = "8a8a8a";
    private static final ExecutorService PHOTO_WORKER = Executors.newSingleThreadExecutor();

    /** A photo on the form: being prepared or uploaded, or already on the server. */
    private static final class Photo {
        String id;
        String url;
        Bitmap thumbnail;
        boolean uploading;
    }

    /** Choices from the previous book, looked up by name in the new one. */
    /** Why the account was set; only a default or a shop's account gives way to another shop's account. */
    private enum AccountSource { DEFAULT, SHOP, CARD, GIVEN }

    private static final class Carried {
        String categoryName;
        String categoryParentName;
        Models.Account account;
        AccountSource accountSource = AccountSource.DEFAULT;
        Models.Account destinationAccount;
        final List<String> tagNames = new ArrayList<>();
        String projectName;
    }

    private Server server;
    private String transactionId;
    private Models.Transaction original;
    private boolean originalApplied;
    /** A change saved on this phone and not sent yet, which this screen edits instead of the server's version. */
    private LocalStore.Change queued;

    private String bookId;
    /** The book an existing transaction is in; saving it in another book moves it there. */
    private String originalBookId;
    /** What was chosen before switching books, to pick the same names again in the new book. */
    private Carried carried;
    private List<Models.Book> books = new ArrayList<>();
    private RecentChoices recent;

    private List<Models.Account> accounts = new ArrayList<>();
    private Object categoriesResult;
    private List<Models.Tag> tags = new ArrayList<>();
    private boolean merchantsSupported;
    private List<String> merchants = new ArrayList<>();
    private boolean projectsSupported;
    private List<Models.Project> projects = new ArrayList<>();
    private boolean photosSupported = true;

    private int type = Models.TRANSACTION_EXPENSE;
    private Models.Category category;
    private Models.Account account;
    private Models.Account destinationAccount;
    private boolean accountChosen;
    private AccountSource accountSource = AccountSource.DEFAULT;
    private boolean destinationChosen;
    private final List<String> tagIds = new ArrayList<>();
    private String merchant = "";
    private PendingPayments.Item pending;
    private String projectId = "0";
    private final List<Photo> photos = new ArrayList<>();
    private final Calendar time = Calendar.getInstance();
    private final String clientSessionId = UUID.randomUUID().toString();

    private TextInputLayout bookLayout;
    private TextInputEditText bookInput;
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
    private View amountKeypad;
    /** What the amount field says under it when it isn't showing a sum's result, such as the card a payment was made with. */
    private CharSequence amountHelper;
    private TextInputEditText dateInput;
    private TextInputEditText timeInput;
    private TextInputLayout projectLayout;
    private TextInputEditText projectInput;
    private TextInputLayout merchantLayout;
    private TextInputEditText merchantInput;
    private TextInputEditText tagsInput;
    private TextInputEditText commentInput;
    private View photosSection;
    private View photosScroll;
    private LinearLayout photosList;
    private MaterialButton saveButton;
    private View progress;
    private int pendingLoads;
    private int loadGeneration;
    private boolean saving;

    private Uri cameraPhoto;
    private ActivityResultLauncher<Uri> takePhoto;
    private ActivityResultLauncher<PickVisualMediaRequest> choosePhoto;

    public static Intent addIntent(Context context, String accountId) {
        return new Intent(context, TransactionEditActivity.class).putExtra(EXTRA_ACCOUNT_ID, accountId);
    }

    /** A new transaction of this type: Models.TRANSACTION_EXPENSE, _INCOME or _TRANSFER. */
    public static Intent addIntent(Context context, int type) {
        return new Intent(context, TransactionEditActivity.class).putExtra(EXTRA_TYPE, type);
    }

    /** Opens a purchase picked up from Wallet, filled in and ready to save. */
    public static Intent reviewIntent(Context context, String pendingId) {
        return new Intent(context, TransactionEditActivity.class).putExtra(EXTRA_PENDING_ID, pendingId);
    }

    public static Intent editIntent(Context context, String transactionId) {
        return new Intent(context, TransactionEditActivity.class).putExtra(EXTRA_TRANSACTION_ID, transactionId);
    }

    /** Opens a transaction saved on this phone that the server hasn't taken yet. */
    public static Intent queuedIntent(Context context, long changeId) {
        return new Intent(context, TransactionEditActivity.class).putExtra(EXTRA_CHANGE_ID, changeId);
    }

    /** Opens the screen in a given book instead of the app's: a shared book's id, or "" for the user's own book; null changes nothing. */
    public static Intent inBook(Intent intent, String bookId) {
        return bookId == null ? intent : intent.putExtra(EXTRA_BOOK_ID, bookId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        server = ServerStore.get(this).getActiveServer();

        takePhoto = registerForActivityResult(new ActivityResultContracts.TakePicture(), taken -> {
            if (Boolean.TRUE.equals(taken) && cameraPhoto != null) {
                addPhoto(cameraPhoto);
            }
        });
        choosePhoto = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) {
                addPhoto(uri);
            }
        });

        if (server == null || !server.isSignedIn()) {
            finish();
            return;
        }

        transactionId = getIntent().getStringExtra(EXTRA_TRANSACTION_ID);
        String pendingId = getIntent().getStringExtra(EXTRA_PENDING_ID);

        if (pendingId != null) {
            pending = PendingPayments.get(this, pendingId);

            if (pending == null) {
                // already saved or dismissed
                finish();
                return;
            }
        }

        long changeId = getIntent().getLongExtra(EXTRA_CHANGE_ID, 0);
        queued = changeId != 0 ? LocalStore.get(this).change(changeId)
            : transactionId != null ? Sync.changeFor(this, server, transactionId) : null;

        if (changeId != 0 && queued == null) {
            // sent meanwhile
            finish();
            return;
        }

        if (queued != null && LocalStore.KIND_DELETE.equals(queued.kind)) {
            // deleted on this phone: show the server's version, and saving it takes the place of the deletion
            queued = null;
        }

        if (queued != null) {
            // the version saved on this phone, newer than the server's
            transactionId = queued.transactionId;
            original = Models.Transaction.parse(queued.body);
            original.id = queued.transactionId;
        }

        String givenBook = getIntent().getStringExtra(EXTRA_BOOK_ID);
        bookId = queued != null ? queued.bookId : givenBook == null ? server.bookId : givenBook.isEmpty() ? null : givenBook;
        originalBookId = bookId;
        recent = new RecentChoices(this, server, bookId);
        setContentView(R.layout.activity_transaction_edit);
        setupToolbar(pending != null ? R.string.review_purchase : transactionId == null ? R.string.add_transaction : R.string.edit_transaction, true).setNavigationIcon(R.drawable.ic_close);
        padForSystemBars(findViewById(R.id.scroll), false, true);

        bookLayout = findViewById(R.id.book_layout);
        bookInput = findViewById(R.id.book);
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
        amountKeypad = findViewById(R.id.amount_keypad);
        dateInput = findViewById(R.id.date);
        timeInput = findViewById(R.id.time);
        projectLayout = findViewById(R.id.project_layout);
        projectInput = findViewById(R.id.project);
        merchantLayout = findViewById(R.id.merchant_layout);
        merchantInput = findViewById(R.id.merchant);
        tagsInput = findViewById(R.id.tags);
        commentInput = findViewById(R.id.comment);
        photosSection = findViewById(R.id.photos_section);
        photosScroll = findViewById(R.id.photos_scroll);
        photosList = findViewById(R.id.photos);
        saveButton = findViewById(R.id.save_button);
        progress = findViewById(R.id.progress);

        if (transactionId == null && pending == null) {
            type = getIntent().getIntExtra(EXTRA_TYPE, Models.TRANSACTION_EXPENSE);
        }

        typeGroup.check(type == Models.TRANSACTION_INCOME ? R.id.type_income : type == Models.TRANSACTION_TRANSFER ? R.id.type_transfer : R.id.type_expense);
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

                if (original == null) {
                    applyRememberedAccounts(null);
                }

                renderAll();
            }
        });

        bookInput.setOnClickListener(view -> chooseBook());
        bookLayout.setEndIconOnClickListener(view -> chooseBook());
        categoryInput.setOnClickListener(view -> chooseCategory());
        categoryLayout.setEndIconOnClickListener(view -> chooseCategory());
        accountInput.setOnClickListener(view -> chooseAccount(false));
        accountLayout.setEndIconOnClickListener(view -> chooseAccount(false));
        destinationAccountInput.setOnClickListener(view -> chooseAccount(true));
        destinationAccountLayout.setEndIconOnClickListener(view -> chooseAccount(true));
        dateInput.setOnClickListener(view -> chooseDate());
        timeInput.setOnClickListener(view -> chooseTime());
        projectInput.setOnClickListener(view -> chooseProject());
        projectLayout.setEndIconOnClickListener(view -> chooseProject());
        merchantInput.setOnClickListener(view -> chooseMerchant());
        merchantLayout.setEndIconOnClickListener(view -> chooseMerchant());
        tagsInput.setOnClickListener(view -> chooseTags());
        ((TextInputLayout) findViewById(R.id.tags_layout)).setEndIconOnClickListener(view -> chooseTags());
        findViewById(R.id.take_photo_button).setOnClickListener(view -> startCamera());
        findViewById(R.id.choose_photo_button).setOnClickListener(view -> choosePhoto.launch(new PickVisualMediaRequest.Builder()
            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
            .build()));
        saveButton.setOnClickListener(view -> save());

        setUpSums(amountLayout, amountInput);
        setUpSums(destinationAmountLayout, destinationAmountInput);
        setUpKeypad();

        if (pending != null) {
            fillFromPending();
        }

        loadBooks();
        loadServerFeatures();
        loadBookData();

        if (transactionId == null && pending == null) {
            // opens the number pad
            amountInput.requestFocus();
        }

        renderAll();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (transactionId != null || queued != null) {
            MenuItem delete = menu.add(0, MENU_DELETE, 0, R.string.delete);
            delete.setIcon(R.drawable.ic_delete);
            delete.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        } else if (pending != null) {
            MenuItem dismiss = menu.add(0, MENU_DISMISS, 0, R.string.dismiss_purchase);
            dismiss.setIcon(R.drawable.ic_delete);
            dismiss.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == MENU_DELETE) {
            confirmDelete();
            return true;
        } else if (item.getItemId() == MENU_DISMISS) {
            PendingPayments.remove(this, pending.id);
            Ui.dataChanged(getApplicationContext());
            finish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    /** Talks to the book this transaction goes in, which can differ from the one selected in the app. */
    private Api api() {
        return new Api(server).withBook(bookId);
    }

    private void loadBooks() {
        new Api(server).withoutBook().get("v1/books/list.json", null, Models.Book::parseList, new Api.Callback<List<Models.Book>>() {
            @Override
            public void onSuccess(List<Models.Book> result) {
                books = result;
                renderAll();
            }

            @Override
            public void onError(ApiException error) {
                // servers without family books answer 404, and then there is nothing to choose
            }
        });
    }

    private void loadServerFeatures() {
        Api.checkServer(server.url, new Api.Callback<Api.ServerInfo>() {
            @Override
            public void onSuccess(Api.ServerInfo result) {
                photosSupported = result.pictures;
                renderAll();
            }

            @Override
            public void onError(ApiException error) {
            }
        });
    }

    /** Loads everything that belongs to the chosen book: accounts, categories, tags, merchants and projects. */
    private void loadBookData() {
        Api api = api();
        int generation = ++loadGeneration;
        setBusy(true);
        // the transaction itself is fetched once; switching books keeps it and reloads only the book's lists
        boolean fetchTransaction = transactionId != null && original == null;
        pendingLoads = fetchTransaction ? 6 : 5;

        api.get("v1/accounts/list.json", Api.query("visible_only", "true"), Models.Account::parseList, new Api.Callback<List<Models.Account>>() {
            @Override
            public void onSuccess(List<Models.Account> result) {
                if (generation == loadGeneration) {
                    accounts = Models.Account.selectable(result);
                    loaded();
                }
            }

            @Override
            public void onError(ApiException error) {
                loadFailed(generation, error);
            }
        });

        api.get("v1/transaction/categories/list.json", null, result -> result, new Api.Callback<Object>() {
            @Override
            public void onSuccess(Object result) {
                if (generation == loadGeneration) {
                    categoriesResult = result;
                    loaded();
                }
            }

            @Override
            public void onError(ApiException error) {
                loadFailed(generation, error);
            }
        });

        api.get("v1/transaction/tags/list.json", null, Models.Tag::parseList, new Api.Callback<List<Models.Tag>>() {
            @Override
            public void onSuccess(List<Models.Tag> result) {
                if (generation == loadGeneration) {
                    tags = result;
                    loaded();
                }
            }

            @Override
            public void onError(ApiException error) {
                // tags are optional
                if (generation == loadGeneration) {
                    loaded();
                }
            }
        });

        api.get("v1/transactions/merchants/list.json", null, TransactionEditActivity::parseMerchants, new Api.Callback<List<String>>() {
            @Override
            public void onSuccess(List<String> result) {
                if (generation == loadGeneration) {
                    merchantsSupported = true;
                    merchants = result;
                    loaded();
                }
            }

            @Override
            public void onError(ApiException error) {
                // only this app's server keeps a merchant on each transaction
                if (generation == loadGeneration) {
                    merchantsSupported = false;
                    loaded();
                }
            }
        });

        api.get("v1/projects/list.json", null, Models.Project::parseList, new Api.Callback<List<Models.Project>>() {
            @Override
            public void onSuccess(List<Models.Project> result) {
                if (generation == loadGeneration) {
                    projectsSupported = true;
                    projects = result;
                    loaded();
                }
            }

            @Override
            public void onError(ApiException error) {
                // only this app's server has projects
                if (generation == loadGeneration) {
                    projectsSupported = false;
                    loaded();
                }
            }
        });

        if (fetchTransaction) {
            api.get("v1/transactions/get.json", Api.query("id", transactionId, "trim_account", "false", "trim_category", "false", "trim_tag", "false"),
                result -> Models.Transaction.parse((JSONObject) result), new Api.Callback<Models.Transaction>() {
                    @Override
                    public void onSuccess(Models.Transaction result) {
                        if (generation == loadGeneration) {
                            original = result;
                            loaded();
                        }
                    }

                    @Override
                    public void onError(ApiException error) {
                        loadFailed(generation, error);
                    }
                });
        }
    }

    private static List<String> parseMerchants(Object result) {
        List<String> names = new ArrayList<>();
        JSONArray array = result instanceof JSONArray ? (JSONArray) result : new JSONArray();

        for (int i = 0; i < array.length(); i++) {
            String name = array.optString(i, "").trim();

            if (!name.isEmpty()) {
                names.add(name);
            }
        }

        return names;
    }

    private void loaded() {
        if (--pendingLoads > 0) {
            return;
        }

        setBusy(false);

        if (original != null) {
            if (!originalApplied) {
                originalApplied = true;
                fillFromOriginal();
            }
        } else {
            String accountId = getIntent().getStringExtra(EXTRA_ACCOUNT_ID);
            account = findAccount(accountId);
            accountChosen = account != null;

            if (account != null) {
                // started from an account's page
                accountSource = AccountSource.GIVEN;
            }

            if (pending != null) {
                applyPendingChoices();
            }

            if (account == null) {
                applyRememberedAccounts(null);

                if (category != null) {
                    applyRememberedAccounts(category.id);
                }
            }
        }

        if (carried != null) {
            applyCarried();
            carried = null;
        }

        renderAll();
    }

    private void loadFailed(int generation, ApiException error) {
        if (generation != loadGeneration) {
            return;
        }

        loadGeneration++;
        setBusy(false);
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.something_went_wrong)
            .setMessage(error.getMessage())
            .setPositiveButton(R.string.retry, (dialog, which) -> loadBookData())
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
        accountChosen = true;
        destinationChosen = true;
        category = findCategory(original.categoryId);
        tagIds.clear();
        tagIds.addAll(original.tagIds);
        merchant = original.merchant == null ? "" : original.merchant;

        projectId = TextUtils.isEmpty(original.projectId) ? "0" : original.projectId;
        time.setTimeInMillis(original.time * 1000);
        commentInput.setText(original.comment);

        if (type == Models.TRANSACTION_TRANSFER) {
            destinationAmountInput.setText(Formats.editableAmount(original.destinationAmount));
        }

        photos.clear();

        for (int i = 0; i < original.pictureIds.size(); i++) {
            Photo photo = new Photo();
            photo.id = original.pictureIds.get(i);
            photo.url = i < original.pictureUrls.size() ? original.pictureUrls.get(i) : null;
            photos.add(photo);
            loadThumbnail(photo);
        }
    }

    /**
     * Lets an amount field take a sum such as 12.50+3.20×2: it is typed on the app's own number pad, which has + − × ÷ ( ) next
     * to the digits, the result shows under the field as it is typed, and OK or leaving the field puts the result in its place.
     */
    private void setUpSums(TextInputLayout layout, TextInputEditText input) {
        // a hardware keyboard can still type sums; the phone's own keyboard stays closed
        input.setKeyListener(DigitsKeyListener.getInstance("0123456789.,+-*/×÷−()"));
        input.setShowSoftInputOnFocus(false);
        // not a text field to the phone's keyboard either, which could otherwise open over the pad as the screen opens
        input.setRawInputType(InputType.TYPE_NULL);

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                layout.setError(null);
            }

            @Override
            public void afterTextChanged(Editable s) {
                showSumResult(layout, s.toString());
            }
        });

        input.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                showKeypad(layout);
            } else {
                workOutSum(input);

                if (!amountInput.hasFocus() && !destinationAmountInput.hasFocus()) {
                    amountKeypad.setVisibility(View.GONE);
                }
            }
        });

        // tapping the field again after OK brings the pad back
        input.setOnClickListener(view -> showKeypad(layout));

        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                workOutSum(input);
            }

            return false;
        });
    }

    private void setUpKeypad() {
        String[] digits = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9"};
        int[] digitKeys = {R.id.key_0, R.id.key_1, R.id.key_2, R.id.key_3, R.id.key_4, R.id.key_5, R.id.key_6, R.id.key_7, R.id.key_8, R.id.key_9};

        for (int i = 0; i < digitKeys.length; i++) {
            String digit = digits[i];
            findViewById(digitKeys[i]).setOnClickListener(view -> typeInAmount(digit));
        }

        // the separator people expect, which the amount reading takes either way
        String point = String.valueOf(DecimalFormatSymbols.getInstance().getDecimalSeparator());
        MaterialButton pointKey = findViewById(R.id.key_point);
        pointKey.setText(point);
        pointKey.setOnClickListener(view -> typeInAmount(point));

        findViewById(R.id.key_plus).setOnClickListener(view -> typeInAmount("+"));
        findViewById(R.id.key_minus).setOnClickListener(view -> typeInAmount("−"));
        findViewById(R.id.key_times).setOnClickListener(view -> typeInAmount("×"));
        findViewById(R.id.key_divide).setOnClickListener(view -> typeInAmount("÷"));
        findViewById(R.id.key_open).setOnClickListener(view -> typeInAmount("("));
        findViewById(R.id.key_close).setOnClickListener(view -> typeInAmount(")"));
        findViewById(R.id.key_clear).setOnClickListener(view -> keypadInput().setText(""));
        findViewById(R.id.key_backspace).setOnClickListener(view -> deleteInAmount());
        findViewById(R.id.key_backspace).setOnLongClickListener(view -> {
            keypadInput().setText("");
            return true;
        });
        findViewById(R.id.key_ok).setOnClickListener(view -> closeKeypad());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (amountKeypad.getVisibility() == View.VISIBLE) {
                    closeKeypad();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });
    }

    private void showKeypad(TextInputLayout layout) {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);

        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(layout.getWindowToken(), 0);
        }

        amountKeypad.setVisibility(View.VISIBLE);
        // keeps the field in sight above the pad
        layout.post(() -> layout.requestRectangleOnScreen(new Rect(0, 0, layout.getWidth(), layout.getHeight())));
    }

    /** Works out the sum, closes the pad and moves on from the field. */
    private void closeKeypad() {
        TextInputEditText input = keypadInput();
        workOutSum(input);
        amountKeypad.setVisibility(View.GONE);
        input.clearFocus();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // touching anything but the pad or the amount field closes the pad, as touching outside a keyboard would
        if (event.getAction() == MotionEvent.ACTION_DOWN && amountKeypad.getVisibility() == View.VISIBLE
            && !isInside(amountKeypad, event) && !isInside(keypadInput(), event)) {
            closeKeypad();
        }

        return super.dispatchTouchEvent(event);
    }

    private static boolean isInside(View view, MotionEvent event) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float x = event.getRawX();
        float y = event.getRawY();
        return x >= location[0] && x < location[0] + view.getWidth() && y >= location[1] && y < location[1] + view.getHeight();
    }

    private TextInputEditText keypadInput() {
        return destinationAmountInput.hasFocus() ? destinationAmountInput : amountInput;
    }

    private void showSumResult(TextInputLayout layout, String text) {
        CharSequence plain = layout == amountLayout ? amountHelper : null;
        Long cents = AmountExpression.isSum(text) ? Formats.parseAmount(text) : null;
        layout.setHelperText(cents == null ? plain : "= " + Formats.editableAmount(cents));
    }

    /** Puts a sum's result in place of the sum; an unfinished sum is left to finish (or to be shown as wrong on save). */
    private void workOutSum(TextInputEditText input) {
        String text = input.getText() == null ? "" : input.getText().toString();

        if (!AmountExpression.isSum(text)) {
            return;
        }

        Long cents = Formats.parseAmount(text);

        if (cents != null) {
            input.setText(Formats.editableAmount(cents));
            input.setSelection(input.length());
        }
    }

    private void typeInAmount(String key) {
        TextInputEditText input = keypadInput();
        Editable text = input.getText();

        if (text == null) {
            return;
        }

        int start = Math.max(0, Math.min(input.getSelectionStart(), input.getSelectionEnd()));
        int end = Math.max(0, Math.max(input.getSelectionStart(), input.getSelectionEnd()));
        text.replace(start, end, key);
    }

    private void deleteInAmount() {
        TextInputEditText input = keypadInput();
        Editable text = input.getText();

        if (text == null) {
            return;
        }

        int start = Math.max(0, Math.min(input.getSelectionStart(), input.getSelectionEnd()));
        int end = Math.max(0, Math.max(input.getSelectionStart(), input.getSelectionEnd()));

        if (start != end) {
            text.delete(start, end);
        } else if (start > 0) {
            text.delete(start - 1, start);
        }
    }

    /** Says the sum can't be worked out (such as 12+ or ÷0) rather than asking for an amount the field already has. */
    private static int amountError(TextInputEditText input) {
        String text = input.getText() == null ? "" : input.getText().toString();
        return AmountExpression.isSum(text) && Formats.parseAmount(text) == null ? R.string.sum_not_valid : R.string.amount_required;
    }

    private void fillFromPending() {
        type = pending.refund ? Models.TRANSACTION_INCOME : Models.TRANSACTION_EXPENSE;
        typeGroup.check(pending.refund ? R.id.type_income : R.id.type_expense);
        amountInput.setText(Formats.editableAmount(pending.amount));
        time.setTimeInMillis(pending.time);
        merchant = pending.merchant;
        String paid = pending.card.isEmpty() ? getString(R.string.google_wallet) : pending.card;
        amountHelper = pending.currency.isEmpty() ? paid : getString(R.string.paid_in_currency, paid, Formats.amount(pending.amount, pending.currency));
        amountLayout.setHelperText(amountHelper);
    }

    /** The category used last time at this shop, and the account this card belongs to or else the one used at this shop. */
    private void applyPendingChoices() {
        if (category == null) {
            String categoryId = recent.categoryForMerchant(pending.merchant);

            for (Models.Category parent : categoriesOfType()) {
                for (Models.Category sub : parent.subCategories) {
                    if (sub.id.equals(categoryId) && !sub.hidden) {
                        category = sub;
                    }
                }
            }
        }

        if (accountChosen) {
            return;
        }

        // the card Wallet names is what was actually used, so it comes before the shop
        Models.Account card = findAccount(recent.accountForCard(pending.cardKey));

        // an account named or noted with the card's last four digits, like "Visa 1234"
        if (card == null && pending.cardKey.matches("\\d{4}")) {
            for (Models.Account candidate : accounts) {
                if ((candidate.name != null && candidate.name.contains(pending.cardKey))
                    || (candidate.comment != null && candidate.comment.contains(pending.cardKey))) {
                    card = candidate;
                    break;
                }
            }
        }

        if (card != null) {
            account = card;
            accountChosen = true;
            accountSource = AccountSource.CARD;
            return;
        }

        Models.Account usedHere = findAccount(recent.accountForMerchant(pending.merchant));

        if (usedHere != null) {
            account = usedHere;
            accountChosen = true;
            accountSource = AccountSource.SHOP;
        }
    }

    /**
     * On a new transaction, a shop picks the account last used there, unless the account was picked by hand,
     * came with the screen (an account's page) or is the card a Wallet purchase was paid with.
     */
    private void applyMerchantAccount() {
        if (original != null || (accountSource != AccountSource.DEFAULT && accountSource != AccountSource.SHOP)) {
            return;
        }

        Models.Account usedHere = findAccount(recent.accountForMerchant(merchant));

        if (usedHere != null) {
            account = usedHere;
            accountChosen = true;
            accountSource = AccountSource.SHOP;
            accountLayout.setError(null);
        }
    }

    /** Starts a new transaction with the accounts used last time for this category, or this kind of transaction. */
    private void applyRememberedAccounts(String categoryId) {
        if (!accountChosen) {
            Models.Account remembered = findAccount(recent.accountForCategory(categoryId, false));

            if (remembered == null && categoryId == null) {
                remembered = findAccount(recent.accountForType(type, false));

                if (remembered == null) {
                    remembered = findAccount(server.getUser().optString("defaultAccountId", "0"));
                }

                if (remembered == null && !accounts.isEmpty()) {
                    remembered = accounts.get(0);
                }
            }

            if (remembered != null) {
                account = remembered;
            }
        }

        if (!destinationChosen && type == Models.TRANSACTION_TRANSFER) {
            Models.Account remembered = findAccount(recent.accountForCategory(categoryId, true));

            if (remembered == null && categoryId == null) {
                remembered = findAccount(recent.accountForType(type, true));
            }

            if (remembered != null) {
                destinationAccount = remembered;
            }
        }
    }

    private void renderAll() {
        boolean transfer = type == Models.TRANSACTION_TRANSFER;
        // a transaction waiting on this phone stays in its book until the server has it
        boolean canChooseBook = books.size() > 1 && queued == null;
        bookLayout.setVisibility(canChooseBook ? View.VISIBLE : View.GONE);
        bookInput.setText(bookLabel(findBook(bookId)));

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

        Models.Project project = findProject(projectId);
        projectLayout.setVisibility(projectsSupported && (!projects.isEmpty() || project != null) ? View.VISIBLE : View.GONE);
        projectInput.setText(project == null ? "" : project.name);

        merchantLayout.setVisibility(merchantsSupported || !merchant.isEmpty() ? View.VISIBLE : View.GONE);
        merchantInput.setText(merchant);

        List<String> names = new ArrayList<>();

        for (Models.Tag tag : tags) {
            if (tagIds.contains(tag.id)) {
                names.add(tag.name);
            }
        }

        tagsInput.setText(TextUtils.join(", ", names));
        renderPhotos();
    }

    private void renderPhotos() {
        photosSection.setVisibility(photosSupported || !photos.isEmpty() ? View.VISIBLE : View.GONE);
        findViewById(R.id.take_photo_button).setVisibility(photosSupported ? View.VISIBLE : View.GONE);
        findViewById(R.id.choose_photo_button).setVisibility(photosSupported ? View.VISIBLE : View.GONE);
        photosScroll.setVisibility(photos.isEmpty() ? View.GONE : View.VISIBLE);
        photosList.removeAllViews();

        int size = Math.round(96 * getResources().getDisplayMetrics().density);
        int gap = Math.round(8 * getResources().getDisplayMetrics().density);

        for (Photo photo : photos) {
            FrameLayout frame = new FrameLayout(this);
            LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(size, size);
            frameParams.setMarginEnd(gap);
            frameParams.topMargin = gap / 2;
            frameParams.bottomMargin = gap / 2;

            ShapeableImageView image = new ShapeableImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setShapeAppearanceModel(ShapeAppearanceModel.builder().setAllCornerSizes(gap * 1.5f).build());
            image.setContentDescription(getString(R.string.photo_description));

            if (photo.thumbnail != null) {
                image.setImageBitmap(photo.thumbnail);
            } else {
                // a placeholder until the preview arrives, drawn as an image so it gets the rounded corners
                image.setImageDrawable(new ColorDrawable(MaterialColors.getColor(image, com.google.android.material.R.attr.colorSurfaceVariant)));
            }

            image.setAlpha(photo.uploading ? 0.5f : 1f);
            frame.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            if (photo.uploading) {
                CircularProgressIndicator spinner = new CircularProgressIndicator(this);
                spinner.setIndeterminate(true);
                frame.addView(spinner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            } else {
                frame.setOnClickListener(view -> new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.remove_photo_title)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.remove, (dialog, which) -> {
                        photos.remove(photo);
                        renderPhotos();
                    })
                    .show());
            }

            photosList.addView(frame, frameParams);
        }
    }

    private String categoryLabel(Models.Category category) {
        Models.Category parent = findParentCategory(category.id);
        return parent == null ? category.name : parent.name + " › " + category.name;
    }

    private String bookLabel(Models.Book book) {
        if (book == null || book.isPersonal) {
            return getString(R.string.my_book);
        }

        return book.isOwner ? book.name : book.name + " · " + getString(R.string.book_owner, book.ownerNickname);
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

    private Models.Book findBook(String id) {
        for (Models.Book book : books) {
            if (id == null ? book.isPersonal : book.id.equals(id)) {
                return book;
            }
        }

        return null;
    }

    private Models.Project findProject(String id) {
        if (id == null || "0".equals(id)) {
            return null;
        }

        for (Models.Project project : projects) {
            if (project.id.equals(id)) {
                return project;
            }
        }

        return null;
    }

    private Models.Tag findTag(String id) {
        if (id == null) {
            return null;
        }

        for (Models.Tag tag : tags) {
            if (tag.id.equals(id)) {
                return tag;
            }
        }

        return null;
    }

    private void chooseBook() {
        List<ChoiceSheet.Choice> choices = new ArrayList<>();

        for (Models.Book book : books) {
            ChoiceSheet.Choice choice = ChoiceSheet.Choice.item(book.isPersonal ? "" : book.id, bookLabel(book), null, null);
            choice.selected = book == findBook(bookId);
            choices.add(choice);
        }

        ChoiceSheet.show(this, getString(R.string.choose_book), choices, choice -> {
            String chosen = choice.id.isEmpty() ? null : choice.id;

            if (TextUtils.equals(chosen, bookId)) {
                return;
            }

            boolean hasEntries = original != null || category != null || !tagIds.isEmpty() || !"0".equals(projectId) || !photos.isEmpty();

            if (!hasEntries) {
                switchBook(chosen);
                return;
            }

            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.book_change_title)
                .setMessage(R.string.book_change_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.change, (dialog, which) -> switchBook(chosen))
                .show();
        });
    }

    private void switchBook(String chosen) {
        carried = new Carried();

        if (category != null) {
            carried.categoryName = category.name;
            Models.Category parent = findParentCategory(category);
            carried.categoryParentName = parent == null ? null : parent.name;
        }

        carried.account = account;
        carried.accountSource = accountSource;
        carried.destinationAccount = destinationAccount;

        for (String tagId : tagIds) {
            Models.Tag tag = findTag(tagId);

            if (tag != null) {
                carried.tagNames.add(tag.name);
            }
        }

        for (Models.Project project : projects) {
            if (project.id.equals(projectId)) {
                carried.projectName = project.name;
            }
        }

        Api previousBook = api();
        bookId = chosen;
        recent = new RecentChoices(this, server, bookId);
        category = null;
        account = null;
        destinationAccount = null;
        accountChosen = false;
        accountSource = AccountSource.DEFAULT;
        destinationChosen = false;
        tagIds.clear();
        merchants = new ArrayList<>();
        projectId = "0";
        copyPhotosFrom(previousBook);
        accounts = new ArrayList<>();
        tags = new ArrayList<>();
        projects = new ArrayList<>();
        categoriesResult = null;
        renderAll();
        loadBookData();
    }

    private Models.Category findParentCategory(Models.Category sub) {
        for (Models.Category parent : categoriesOfType()) {
            if (parent.id.equals(sub.parentId)) {
                return parent;
            }
        }

        return null;
    }

    /** Picks the category, accounts, tags and project with the same names in the new book. */
    private void applyCarried() {
        if (carried.categoryName != null) {
            Models.Category sameName = null;

            for (Models.Category parent : categoriesOfType()) {
                for (Models.Category sub : parent.subCategories) {
                    if (sub.hidden || !sub.name.equalsIgnoreCase(carried.categoryName)) {
                        continue;
                    }

                    if (parent.name.equalsIgnoreCase(carried.categoryParentName)) {
                        category = sub;
                    } else if (sameName == null) {
                        sameName = sub;
                    }
                }
            }

            if (category == null) {
                category = sameName;
            }
        }

        Models.Account sameAccount = findCarriedAccount(carried.account);

        if (sameAccount != null) {
            account = sameAccount;
            accountChosen = true;
            accountSource = carried.accountSource;
        }

        Models.Account sameDestination = findCarriedAccount(carried.destinationAccount);

        if (sameDestination != null) {
            destinationAccount = sameDestination;
            destinationChosen = true;
        }

        for (String name : carried.tagNames) {
            for (Models.Tag tag : tags) {
                if (tag.name.equalsIgnoreCase(name) && !tagIds.contains(tag.id)) {
                    tagIds.add(tag.id);
                    break;
                }
            }
        }

        for (Models.Project project : projects) {
            if (project.name.equalsIgnoreCase(carried.projectName)) {
                projectId = project.id;
            }
        }
    }

    /** An account shared with both books keeps its id; otherwise one with the same name and currency. */
    private Models.Account findCarriedAccount(Models.Account previous) {
        if (previous == null) {
            return null;
        }

        Models.Account same = findAccount(previous.id);

        if (same != null) {
            return same;
        }

        for (Models.Account candidate : accounts) {
            if (TextUtils.equals(candidate.name, previous.name) && TextUtils.equals(candidate.currency, previous.currency)) {
                return candidate;
            }
        }

        return null;
    }

    /** Photos belong to a book, so each one is uploaded again to the newly chosen book. */
    private void copyPhotosFrom(Api previousBook) {
        List<Photo> copies = new ArrayList<>();

        for (Photo photo : photos) {
            if (photo.uploading || TextUtils.isEmpty(photo.url)) {
                continue;
            }

            Photo copy = new Photo();
            copy.url = photo.url;
            copy.thumbnail = photo.thumbnail;
            copy.uploading = true;
            copies.add(copy);
        }

        photos.clear();
        photos.addAll(copies);
        Api newBook = api();

        for (Photo copy : copies) {
            previousBook.download(pictureUrl(copy.url), new Api.Callback<byte[]>() {
                @Override
                public void onSuccess(byte[] result) {
                    PHOTO_WORKER.execute(() -> {
                        Bitmap bitmap = BitmapFactory.decodeByteArray(result, 0, result.length);
                        ByteArrayOutputStream output = new ByteArrayOutputStream();

                        if (bitmap != null) {
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output);
                        }

                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }

                            if (bitmap == null) {
                                photos.remove(copy);
                                renderPhotos();
                                showMessage(getString(R.string.photo_unreadable));
                            } else {
                                uploadPhoto(copy, newBook, output.toByteArray());
                            }
                        });
                    });
                }

                @Override
                public void onError(ApiException error) {
                    photoUploadFailed(copy, error);
                }
            });
        }
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

        choices.add(ChoiceSheet.Choice.symbol("+", getString(R.string.add_category), "+"));

        ChoiceSheet.show(this, getString(R.string.choose_category), choices, choice -> {
            if ("+".equals(choice.id)) {
                chooseCategoryGroup();
                return;
            }

            category = findCategory(choice.id);
            categoryLayout.setError(null);

            if (original == null && category != null) {
                applyRememberedAccounts(category.id);
            }

            renderAll();
        });
    }

    private void chooseAccount(boolean destination) {
        List<ChoiceSheet.Choice> choices = new ArrayList<>();
        Models.Account current = destination ? destinationAccount : account;
        List<Integer> categories = new ArrayList<>();

        for (int accountCategory : Ui.ACCOUNT_CATEGORY_ORDER) {
            categories.add(accountCategory);
        }

        for (Models.Account candidate : accounts) {
            if (!categories.contains(candidate.category)) {
                categories.add(candidate.category);
            }
        }

        for (int accountCategory : categories) {
            boolean headerAdded = false;

            for (Models.Account candidate : accounts) {
                if (candidate.category != accountCategory) {
                    continue;
                }

                if (!headerAdded) {
                    choices.add(ChoiceSheet.Choice.header(Ui.accountCategoryName(this, accountCategory)));
                    headerAdded = true;
                }

                ChoiceSheet.Choice choice = ChoiceSheet.Choice.item(candidate.id, candidate.name, Formats.amount(candidate.balance, candidate.currency), candidate.color);
                choice.selected = current != null && current.id.equals(candidate.id);
                choices.add(choice);
            }
        }

        if (choices.isEmpty()) {
            showMessage(getString(R.string.no_accounts_hint));
            return;
        }

        ChoiceSheet.show(this, getString(R.string.choose_account), choices, choice -> {
            if (destination) {
                destinationAccount = findAccount(choice.id);
                destinationChosen = true;
                destinationAccountLayout.setError(null);
            } else {
                account = findAccount(choice.id);
                accountChosen = true;
                accountSource = AccountSource.GIVEN;
                accountLayout.setError(null);
            }

            renderAll();
        });
    }

    private void chooseProject() {
        List<ChoiceSheet.Choice> choices = new ArrayList<>();
        ChoiceSheet.Choice none = ChoiceSheet.Choice.symbol("0", getString(R.string.no_project), "–");
        none.selected = findProject(projectId) == null;
        choices.add(none);

        for (Models.Project project : projects) {
            if (project.hidden && !project.id.equals(projectId)) {
                continue;
            }

            ChoiceSheet.Choice choice = ChoiceSheet.Choice.item(project.id, project.name, null, null);
            choice.selected = project.id.equals(projectId);
            choices.add(choice);
        }

        ChoiceSheet.show(this, getString(R.string.choose_project), choices, choice -> {
            projectId = choice.id;
            renderAll();
        });
    }

    /** A new category goes in a group, like Restaurants in Food; the group can be new too. */
    private void chooseCategoryGroup() {
        List<ChoiceSheet.Choice> choices = new ArrayList<>();
        List<Models.Category> groups = categoriesOfType();

        for (Models.Category group : groups) {
            if (!group.hidden) {
                choices.add(ChoiceSheet.Choice.item(group.id, group.name, null, group.color));
            }
        }

        choices.add(ChoiceSheet.Choice.symbol("+", getString(R.string.new_category_group), "+"));

        ChoiceSheet.show(this, getString(R.string.choose_category_group), choices, choice -> {
            Models.Category group = null;

            for (Models.Category candidate : groups) {
                if (candidate.id.equals(choice.id)) {
                    group = candidate;
                }
            }

            addCategory(group);
        });
    }

    private void addCategory(Models.Category group) {
        int padding = getResources().getDimensionPixelSize(R.dimen.dialog_padding);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(padding, padding / 2, padding, 0);
        TextInputEditText groupInput = group == null ? addNameField(form, R.string.category_group_name) : null;
        TextInputEditText nameInput = addNameField(form, R.string.category_name);
        (groupInput != null ? groupInput : nameInput).requestFocus();

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setTitle(group == null ? getString(R.string.new_category) : getString(R.string.new_category_in, group.name))
            .setView(form)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.add, null)
            .show();
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);

        // checked here rather than in the button's listener, so an empty name keeps the dialog open
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String groupName = groupInput == null || groupInput.getText() == null ? "" : groupInput.getText().toString().trim();
            String name = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();

            if (groupInput != null && groupName.isEmpty()) {
                groupInput.setError(getString(R.string.name_required));
                return;
            }

            if (name.isEmpty()) {
                nameInput.setError(getString(R.string.name_required));
                return;
            }

            dialog.dismiss();

            if (group != null) {
                createCategory(name, group.id, group.icon, group.color);
                return;
            }

            setBusy(true);
            postCategory(groupName, "0", NEW_CATEGORY_ICON, NEW_CATEGORY_COLOR, newGroup -> createCategory(name, newGroup.id, newGroup.icon, newGroup.color));
        });
    }

    private TextInputEditText addNameField(LinearLayout form, int hint) {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(getString(hint));
        layout.setCounterEnabled(true);
        layout.setCounterMaxLength(MAX_CATEGORY_NAME_LENGTH);
        TextInputEditText input = new TextInputEditText(layout.getContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setFilters(new InputFilter[] { new InputFilter.LengthFilter(MAX_CATEGORY_NAME_LENGTH) });
        input.setSingleLine(true);
        layout.addView(input);
        form.addView(layout);
        return input;
    }

    private void createCategory(String name, String groupId, String icon, String color) {
        setBusy(true);
        postCategory(name, groupId, icon, color, created -> reloadCategories(created.id));
    }

    private void postCategory(String name, String parentId, String icon, String color, java.util.function.Consumer<Models.Category> then) {
        JSONObject body = new JSONObject();

        try {
            body.put("name", name);
            body.put("type", Models.categoryTypeOf(type));
            body.put("parentId", parentId);
            body.put("icon", TextUtils.isEmpty(icon) ? NEW_CATEGORY_ICON : icon);
            body.put("color", TextUtils.isEmpty(color) ? NEW_CATEGORY_COLOR : color);
            body.put("comment", "");
            body.put("clientSessionId", UUID.randomUUID().toString());
        } catch (JSONException ex) {
            return;
        }

        api().post("v1/transaction/categories/add.json", body, result -> Models.Category.parse((JSONObject) result), new Api.Callback<Models.Category>() {
            @Override
            public void onSuccess(Models.Category result) {
                then.accept(result);
            }

            @Override
            public void onError(ApiException error) {
                setBusy(false);
                showMessage(error.getMessage());
            }
        });
    }

    /** Fetches the categories again and picks the one just added. */
    private void reloadCategories(String selectId) {
        api().get("v1/transaction/categories/list.json", null, result -> result, new Api.Callback<Object>() {
            @Override
            public void onSuccess(Object result) {
                setBusy(false);
                categoriesResult = result;
                Models.Category added = findCategory(selectId);

                if (added != null) {
                    category = added;
                    categoryLayout.setError(null);
                }

                renderAll();
            }

            @Override
            public void onError(ApiException error) {
                setBusy(false);
                showMessage(error.getMessage());
            }
        });
    }

    private void chooseMerchant() {
        List<ChoiceSheet.Choice> choices = new ArrayList<>();
        ChoiceSheet.Choice none = ChoiceSheet.Choice.symbol("", getString(R.string.no_merchant), "–");
        none.selected = merchant.isEmpty();
        choices.add(none);
        List<String> names = new ArrayList<>(merchants);

        if (!merchant.isEmpty() && !names.contains(merchant)) {
            names.add(0, merchant);
        }

        for (String name : names) {
            ChoiceSheet.Choice choice = ChoiceSheet.Choice.item("=" + name, name, null, null);
            choice.selected = name.equals(merchant);
            choices.add(choice);
        }

        choices.add(ChoiceSheet.Choice.symbol("+", getString(R.string.add_merchant), "+"));

        ChoiceSheet.show(this, getString(R.string.choose_merchant), choices, choice -> {
            if ("+".equals(choice.id)) {
                addMerchant();
            } else {
                merchant = choice.id.isEmpty() ? "" : choice.id.substring(1);
                applyMerchantAccount();
                renderAll();
            }
        });
    }

    private void addMerchant() {
        int padding = getResources().getDimensionPixelSize(R.dimen.dialog_padding);
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(getString(R.string.merchant_name));
        layout.setPadding(padding, padding / 2, padding, 0);
        layout.setCounterEnabled(true);
        layout.setCounterMaxLength(MAX_MERCHANT_LENGTH);
        TextInputEditText input = new TextInputEditText(layout.getContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setFilters(new InputFilter[] { new InputFilter.LengthFilter(MAX_MERCHANT_LENGTH) });
        input.setSingleLine(true);
        layout.addView(input);
        input.requestFocus();

        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_merchant)
            .setView(layout)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.add, (dialog, which) -> {
                String name = input.getText() == null ? "" : input.getText().toString().trim();

                if (name.isEmpty()) {
                    return;
                }

                // reuse the spelling already in use, so one shop doesn't show up twice in reports
                for (String existing : merchants) {
                    if (existing.equalsIgnoreCase(name)) {
                        name = existing;
                        break;
                    }
                }

                merchant = name;
                applyMerchantAccount();
                renderAll();
            })
            .show()
            .getWindow()
            .setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
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

    private void startCamera() {
        File folder = new File(getCacheDir(), "photos");

        if (!folder.isDirectory() && !folder.mkdirs()) {
            showMessage(getString(R.string.photo_unreadable));
            return;
        }

        File file = new File(folder, UUID.randomUUID() + ".jpg");
        cameraPhoto = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
        takePhoto.launch(cameraPhoto);
    }

    /** Shrinks the photo, shows it on the form and uploads it to the server in the background. */
    private void addPhoto(Uri uri) {
        Photo photo = new Photo();
        photo.uploading = true;
        photos.add(photo);
        renderPhotos();
        Api uploadApi = api();

        PHOTO_WORKER.execute(() -> {
            Bitmap bitmap = readScaledBitmap(uri, MAX_PHOTO_SIZE);
            byte[] jpeg = null;
            Bitmap thumbnail = null;

            if (bitmap != null) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output);
                jpeg = output.toByteArray();
                thumbnail = thumbnailOf(bitmap);
            }

            byte[] data = jpeg;
            Bitmap preview = thumbnail;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }

                if (data == null) {
                    photos.remove(photo);
                    renderPhotos();
                    showMessage(getString(R.string.photo_unreadable));
                    return;
                }

                photo.thumbnail = preview;
                renderPhotos();
                uploadPhoto(photo, uploadApi, data);
            });
        });
    }

    private void uploadPhoto(Photo photo, Api uploadApi, byte[] jpeg) {
        uploadApi.upload("v1/transaction/pictures/upload.json", "picture", "photo.jpg", "image/jpeg", jpeg,
            Api.query("clientSessionId", UUID.randomUUID().toString()), Models.Picture::parse, new Api.Callback<Models.Picture>() {
                @Override
                public void onSuccess(Models.Picture result) {
                    photo.id = result.id;
                    photo.url = result.url;
                    photo.uploading = false;
                    renderPhotos();
                }

                @Override
                public void onError(ApiException error) {
                    photoUploadFailed(photo, error);
                }
            });
    }

    private void photoUploadFailed(Photo photo, ApiException error) {
        photos.remove(photo);
        renderPhotos();
        showMessage(getString(R.string.photo_upload_failed, error.getMessage()));
    }

    private void loadThumbnail(Photo photo) {
        if (TextUtils.isEmpty(photo.url)) {
            return;
        }

        api().download(pictureUrl(photo.url), new Api.Callback<byte[]>() {
            @Override
            public void onSuccess(byte[] result) {
                PHOTO_WORKER.execute(() -> {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(result, 0, result.length);
                    Bitmap thumbnail = bitmap == null ? null : thumbnailOf(bitmap);

                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed() && thumbnail != null) {
                            photo.thumbnail = thumbnail;
                            renderPhotos();
                        }
                    });
                });
            }

            @Override
            public void onError(ApiException error) {
                // the photo stays attached; only its preview is missing
            }
        });
    }

    /** The server builds picture links from its configured root address, which may not be how this phone reaches it. */
    private String pictureUrl(String url) {
        int path = url.indexOf("/pictures/");
        return path >= 0 ? server.url + url.substring(path) : url;
    }

    private Bitmap readScaledBitmap(Uri uri, int maxSize) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;

            try (InputStream input = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(input, null, bounds);
            }

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;

            while (Math.max(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= maxSize) {
                options.inSampleSize *= 2;
            }

            Bitmap bitmap;

            try (InputStream input = getContentResolver().openInputStream(uri)) {
                bitmap = BitmapFactory.decodeStream(input, null, options);
            }

            if (bitmap == null) {
                return null;
            }

            int orientation;

            try (InputStream input = getContentResolver().openInputStream(uri)) {
                orientation = input == null ? ExifInterface.ORIENTATION_NORMAL
                    : new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            }

            float scale = Math.min(1f, (float) maxSize / Math.max(bitmap.getWidth(), bitmap.getHeight()));
            Matrix matrix = new Matrix();
            matrix.postScale(scale, scale);

            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) {
                matrix.postRotate(90);
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) {
                matrix.postRotate(180);
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) {
                matrix.postRotate(270);
            }

            if (matrix.isIdentity()) {
                return bitmap;
            }

            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        } catch (IOException | SecurityException | OutOfMemoryError ex) {
            return null;
        }
    }

    private static Bitmap thumbnailOf(Bitmap bitmap) {
        int size = 320;
        float scale = Math.min(1f, (float) size / Math.min(bitmap.getWidth(), bitmap.getHeight()));
        return Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)), Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
    }

    private void save() {
        if (saving) {
            return;
        }

        for (Photo photo : photos) {
            if (photo.uploading) {
                showMessage(getString(R.string.photo_still_uploading));
                return;
            }
        }

        Long amount = Formats.parseAmount(amountInput.getText() == null ? "" : amountInput.getText().toString());
        boolean valid = true;

        if (amount == null || amount < 0) {
            amountLayout.setError(getString(amountError(amountInput)));
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
                    destinationAmountLayout.setError(getString(amountError(destinationAmountInput)));
                    valid = false;
                }
            }
        }

        if (!valid) {
            return;
        }

        List<String> pictureIds = new ArrayList<>();

        for (Photo photo : photos) {
            if (photo.id != null) {
                pictureIds.add(photo.id);
            }
        }

        if (queued != null && photos.isEmpty()) {
            // photos of a transaction waiting on this phone aren't shown, but stay with it
            JSONArray queuedPictures = queued.body.optJSONArray("pictureIds");

            for (int i = 0; queuedPictures != null && i < queuedPictures.length(); i++) {
                pictureIds.add(queuedPictures.optString(i));
            }
        }

        JSONObject body = new JSONObject();

        boolean moving = original != null && !TextUtils.equals(bookId, originalBookId);

        try {
            if (original != null && !moving && original.id != null) {
                body.put("id", original.id);
            } else {
                // the same id each time this new transaction is sent, so the server can tell a repeat
                body.put("clientSessionId", queued != null ? queued.body.optString("clientSessionId", clientSessionId) : clientSessionId);
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
            body.put("pictureIds", new JSONArray(pictureIds));
            body.put("projectId", projectId == null ? "0" : projectId);

            if (merchantsSupported || !merchant.isEmpty()) {
                body.put("merchant", merchant);
            }
            body.put("comment", commentInput.getText() == null ? "" : commentInput.getText().toString().trim());

            if (original != null && original.geoLocation != null) {
                body.put("geoLocation", original.geoLocation);
            }
        } catch (JSONException ex) {
            return;
        }

        if (moving && !Sync.hasNetwork(this)) {
            showMessage(getString(R.string.move_needs_connection));
            return;
        }

        saving = true;
        setBusy(true);

        if (moving) {
            moveToBook(body);
            return;
        }

        if (queued != null || !Sync.hasNetwork(this)) {
            saveOnPhone(body);
            return;
        }

        api().post(original == null ? "v1/transactions/add.json" : "v1/transactions/modify.json", body, null, new Api.Callback<Object>() {
            @Override
            public void onSuccess(Object result) {
                rememberChoices();
                Ui.dataChanged(getApplicationContext());
                finish();
            }

            @Override
            public void onError(ApiException error) {
                if (error.unreachable) {
                    saveOnPhone(body);
                    return;
                }

                saving = false;
                setBusy(false);
                showMessage(error.getMessage());
            }
        });
    }

    private void rememberChoices() {
        String destinationId = type == Models.TRANSACTION_TRANSFER && destinationAccount != null ? destinationAccount.id : null;
        recent.remember(type, category.id, account.id, destinationId);
        recent.rememberMerchant(merchant, category.id, account.id);

        if (pending != null) {
            recent.rememberCard(pending.cardKey, account.id);
            // Wallet keeps using its own name for the shop even if it was renamed here
            recent.rememberMerchant(pending.merchant, category.id, account.id);
            PendingPayments.remove(this, pending.id);
        }
    }

    /** Keeps the transaction on this phone while the server can't be reached; it is sent once it answers again. */
    private void saveOnPhone(JSONObject body) {
        JSONObject labels = ChangeViews.labels(category, account, type == Models.TRANSACTION_TRANSFER ? destinationAccount : null);

        if (queued != null) {
            try {
                // also when it was sent while this screen was open: then it changes what the server made of it
                Sync.saveEdit(this, server, queued, body, labels);
            } catch (JSONException ex) {
                saving = false;
                setBusy(false);
                showMessage(getString(R.string.error_unexpected_response));
                return;
            }
        } else {
            Sync.queue(this, server, bookId, original == null ? LocalStore.KIND_ADD : LocalStore.KIND_MODIFY,
                original == null ? null : original.id, body, original == null ? null : original.json, labels);
        }

        rememberChoices();
        Toast.makeText(getApplicationContext(), getString(R.string.saved_on_phone), Toast.LENGTH_LONG).show();
        Ui.dataChanged(getApplicationContext());
        finish();
    }

    private void deleteOnPhone(JSONObject body) {
        Sync.queue(this, server, originalBookId, LocalStore.KIND_DELETE, original.id, body, original.json,
            ChangeViews.labels(category, account, destinationAccount));
        Toast.makeText(getApplicationContext(), getString(R.string.deleted_on_phone), Toast.LENGTH_LONG).show();
        Ui.dataChanged(getApplicationContext());
        finish();
    }

    /** Moves the transaction in one step on servers that can, or by adding it to the new book and deleting the old one. */
    private void moveToBook(JSONObject body) {
        JSONObject moveBody;

        try {
            moveBody = new JSONObject(body.toString());
            moveBody.put("id", original.id);
            moveBody.put("sourceBookId", findBook(originalBookId).id);
        } catch (JSONException | NullPointerException ex) {
            addThenRemoveFromOriginalBook(body);
            return;
        }

        api().post("v1/transactions/move/book.json", moveBody, null, new Api.Callback<Object>() {
            @Override
            public void onSuccess(Object result) {
                Ui.dataChanged(getApplicationContext());
                finish();
            }

            @Override
            public void onError(ApiException error) {
                if (error.isNotFound()) {
                    // servers before the move request answer 404
                    addThenRemoveFromOriginalBook(body);
                    return;
                }

                saving = false;
                setBusy(false);
                showMessage(error.getMessage());
            }
        });
    }

    private void addThenRemoveFromOriginalBook(JSONObject body) {
        api().post("v1/transactions/add.json", body, null, new Api.Callback<Object>() {
            @Override
            public void onSuccess(Object result) {
                removeFromOriginalBook();
            }

            @Override
            public void onError(ApiException error) {
                saving = false;
                setBusy(false);
                showMessage(error.getMessage());
            }
        });
    }

    private void removeFromOriginalBook() {
        JSONObject body = new JSONObject();

        try {
            body.put("id", original.id);
        } catch (JSONException ex) {
            return;
        }

        originalBookApi().post("v1/transactions/delete.json", body, null, new Api.Callback<Object>() {
            @Override
            public void onSuccess(Object result) {
                Ui.dataChanged(getApplicationContext());
                finish();
            }

            @Override
            public void onError(ApiException error) {
                Ui.dataChanged(getApplicationContext());
                new MaterialAlertDialogBuilder(TransactionEditActivity.this)
                    .setTitle(R.string.move_incomplete_title)
                    .setMessage(getString(R.string.move_incomplete_message, bookLabel(findBook(originalBookId)), error.getMessage()))
                    .setPositiveButton(R.string.ok, null)
                    .setOnDismissListener(dialog -> finish())
                    .show();
            }
        });
    }

    private Api originalBookApi() {
        return new Api(server).withBook(originalBookId);
    }

    private void confirmDelete() {
        if (original == null) {
            return;
        }

        if (queued != null) {
            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_transaction_title)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> deleteQueued())
                .show();
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

                if (!Sync.hasNetwork(this)) {
                    deleteOnPhone(body);
                    return;
                }

                setBusy(true);
                originalBookApi().post("v1/transactions/delete.json", body, null, new Api.Callback<Object>() {
                    @Override
                    public void onSuccess(Object result) {
                        Ui.dataChanged(getApplicationContext());
                        finish();
                    }

                    @Override
                    public void onError(ApiException error) {
                        if (error.unreachable) {
                            deleteOnPhone(body);
                            return;
                        }

                        setBusy(false);
                        showMessage(error.getMessage());
                    }
                });
            })
            .show();
    }

    /** A transaction added on this phone is simply dropped; one the server has is deleted there once it can be reached. */
    private void deleteQueued() {
        try {
            Sync.deleteEdit(this, server, queued);
        } catch (JSONException ex) {
            showMessage(getString(R.string.error_unexpected_response));
            return;
        }

        Ui.dataChanged(getApplicationContext());
        finish();
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        saveButton.setEnabled(!busy);
    }
}
