package com.ssiuuk.ezbookkeeping;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.ColorDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
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
import com.ssiuuk.ezbookkeeping.data.Api;
import com.ssiuuk.ezbookkeeping.data.ApiException;
import com.ssiuuk.ezbookkeeping.data.Formats;
import com.ssiuuk.ezbookkeeping.data.Models;
import com.ssiuuk.ezbookkeeping.data.RecentChoices;
import com.ssiuuk.ezbookkeeping.data.Server;
import com.ssiuuk.ezbookkeeping.data.ServerStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
    private static final int MAX_PHOTO_SIZE = 2048;
    private static final int MAX_MERCHANT_LENGTH = 64;
    private static final ExecutorService PHOTO_WORKER = Executors.newSingleThreadExecutor();

    /** A photo on the form: being prepared or uploaded, or already on the server. */
    private static final class Photo {
        String id;
        String url;
        Bitmap thumbnail;
        boolean uploading;
    }

    private Server server;
    private String transactionId;
    private Models.Transaction original;
    private boolean originalApplied;

    private String bookId;
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
    private boolean destinationChosen;
    private final List<String> tagIds = new ArrayList<>();
    private String merchant = "";
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

    public static Intent editIntent(Context context, String transactionId) {
        return new Intent(context, TransactionEditActivity.class).putExtra(EXTRA_TRANSACTION_ID, transactionId);
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
        bookId = server.bookId;
        recent = new RecentChoices(this, server, bookId);
        setContentView(R.layout.activity_transaction_edit);
        setupToolbar(transactionId == null ? R.string.add_transaction : R.string.edit_transaction, true).setNavigationIcon(R.drawable.ic_close);
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

        loadBooks();
        loadServerFeatures();
        loadBookData();

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

    /** Talks to the book this transaction goes in, which can differ from the one selected in the app. */
    private Api api() {
        return new Api(server).withBook(bookId);
    }

    private void loadBooks() {
        if (transactionId != null) {
            // an existing transaction stays in its book
            return;
        }

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
        pendingLoads = transactionId == null ? 5 : 6;

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

        if (transactionId != null) {
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

            if (account == null) {
                applyRememberedAccounts(null);
            }
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
        boolean canChooseBook = transactionId == null && books.size() > 1;
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

            boolean hasEntries = category != null || !tagIds.isEmpty() || !merchant.isEmpty() || !"0".equals(projectId) || !photos.isEmpty();

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
        bookId = chosen;
        recent = new RecentChoices(this, server, bookId);
        category = null;
        account = null;
        destinationAccount = null;
        accountChosen = false;
        destinationChosen = false;
        tagIds.clear();
        merchant = "";
        merchants = new ArrayList<>();
        projectId = "0";
        photos.clear();
        accounts = new ArrayList<>();
        tags = new ArrayList<>();
        projects = new ArrayList<>();
        categoriesResult = null;
        renderAll();
        loadBookData();
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
                uploadApi.upload("v1/transaction/pictures/upload.json", "picture", "photo.jpg", "image/jpeg", data,
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
                            photos.remove(photo);
                            renderPhotos();
                            showMessage(getString(R.string.photo_upload_failed, error.getMessage()));
                        }
                    });
            });
        });
    }

    private void loadThumbnail(Photo photo) {
        if (TextUtils.isEmpty(photo.url)) {
            return;
        }

        // the server builds picture links from its configured root address, which may not be how this phone reaches it
        int path = photo.url.indexOf("/pictures/");
        String url = path >= 0 ? server.url + photo.url.substring(path) : photo.url;

        api().download(url, new Api.Callback<byte[]>() {
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

        List<String> pictureIds = new ArrayList<>();

        for (Photo photo : photos) {
            if (photo.id != null) {
                pictureIds.add(photo.id);
            }
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

        saving = true;
        setBusy(true);
        String savedCategoryId = category.id;
        String savedAccountId = account.id;
        String savedDestinationId = type == Models.TRANSACTION_TRANSFER ? destinationAccount.id : null;
        int savedType = type;

        api().post(original == null ? "v1/transactions/add.json" : "v1/transactions/modify.json", body, null, new Api.Callback<Object>() {
            @Override
            public void onSuccess(Object result) {
                recent.remember(savedType, savedCategoryId, savedAccountId, savedDestinationId);
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
                api().post("v1/transactions/delete.json", body, null, new Api.Callback<Object>() {
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
