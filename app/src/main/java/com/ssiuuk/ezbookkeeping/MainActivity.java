package com.ssiuuk.ezbookkeeping;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SearchView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.ssiuuk.ezbookkeeping.data.Api;
import com.ssiuuk.ezbookkeeping.data.ApiException;
import com.ssiuuk.ezbookkeeping.data.Models;
import com.ssiuuk.ezbookkeeping.data.Server;
import com.ssiuuk.ezbookkeeping.data.ServerStore;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** The app's home: overview, transactions, accounts and more, switched with the bottom bar. */
public class MainActivity extends BaseActivity {
    private static final String STATE_TAB = "tab";
    private static final List<WeakReference<Activity>> OPEN = new ArrayList<>();

    private Server server;
    private BottomNavigationView bottomNav;
    private ExtendedFloatingActionButton fab;
    private MaterialToolbar toolbar;
    private int currentTab = R.id.nav_overview;
    private List<Models.Book> books = new ArrayList<>();
    private boolean serverHasBooks;
    private MenuItem searchItem;
    private MenuItem bookItem;

    /** Closes the app screens of the previous server after switching servers. */
    static void finishAll(Activity caller) {
        for (WeakReference<Activity> reference : new ArrayList<>(OPEN)) {
            Activity activity = reference.get();

            if (activity != null && activity != caller) {
                activity.finish();
            }
        }

        OPEN.clear();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        server = ServerStore.get(this).getActiveServer();

        if (server == null || !server.isSignedIn()) {
            startActivity(LaunchActivity.nextScreen(this, false));
            finish();
            return;
        }

        OPEN.add(new WeakReference<>(this));
        setContentView(R.layout.activity_main);
        toolbar = setupToolbar(R.string.overview, false);
        bottomNav = findViewById(R.id.bottom_nav);
        fab = findViewById(R.id.fab);

        fab.setOnClickListener(view -> startActivity(TransactionEditActivity.addIntent(this, null)));
        bottomNav.setOnItemSelectedListener(item -> {
            switchTo(item.getItemId());
            return true;
        });
        bottomNav.setOnItemReselectedListener(item -> {
        });

        if (savedInstanceState != null) {
            currentTab = savedInstanceState.getInt(STATE_TAB, R.id.nav_overview);
        }

        bottomNav.setSelectedItemId(currentTab);
        switchTo(currentTab);
        loadBooks();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (ServerStore.get(this).getActiveServer() != server) {
            startActivity(LaunchActivity.nextScreen(this, false));
            finish();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        searchItem = menu.findItem(R.id.action_search);
        bookItem = menu.findItem(R.id.action_book);

        SearchView searchView = (SearchView) searchItem.getActionView();

        if (searchView != null) {
            searchView.setQueryHint(getString(R.string.search_hint));
            searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    search(query);
                    searchView.clearFocus();
                    return true;
                }

                @Override
                public boolean onQueryTextChange(String newText) {
                    if (newText.isEmpty()) {
                        search(null);
                    }

                    return false;
                }
            });
        }

        searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(@NonNull MenuItem item) {
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(@NonNull MenuItem item) {
                search(null);
                return true;
            }
        });

        updateMenu();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }

        if (item.getItemId() == R.id.action_book) {
            chooseBook();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    boolean serverHasBooks() {
        return serverHasBooks;
    }

    void showTab(int tabId) {
        bottomNav.setSelectedItemId(tabId);
    }

    private void switchTo(int tabId) {
        currentTab = tabId;
        FragmentManager manager = getSupportFragmentManager();
        FragmentTransaction transaction = manager.beginTransaction();
        String tag = "tab" + tabId;

        for (Fragment fragment : manager.getFragments()) {
            if (!tag.equals(fragment.getTag())) {
                transaction.hide(fragment);
            }
        }

        Fragment fragment = manager.findFragmentByTag(tag);

        if (fragment == null) {
            transaction.add(R.id.fragment_container, createTab(tabId), tag);
        } else {
            transaction.show(fragment);
        }

        transaction.setReorderingAllowed(true).commitNow();

        // hidden fragments miss changes made meanwhile, so let the shown one catch up
        if (fragment instanceof Reloadable) {
            ((Reloadable) fragment).reloadIfStale();
        }

        toolbar.setTitle(titleOf(tabId));
        updateSubtitle();

        if (tabId == R.id.nav_overview || tabId == R.id.nav_transactions) {
            fab.show();
        } else {
            fab.hide();
        }

        if (searchItem != null && searchItem.isActionViewExpanded() && tabId != R.id.nav_transactions) {
            searchItem.collapseActionView();
        }

        updateMenu();
    }

    private Fragment createTab(int tabId) {
        if (tabId == R.id.nav_transactions) {
            return new TransactionsFragment();
        } else if (tabId == R.id.nav_accounts) {
            return new AccountsFragment();
        } else if (tabId == R.id.nav_more) {
            return new MoreFragment();
        }

        return new OverviewFragment();
    }

    private int titleOf(int tabId) {
        if (tabId == R.id.nav_transactions) {
            return R.string.transactions;
        } else if (tabId == R.id.nav_accounts) {
            return R.string.accounts;
        } else if (tabId == R.id.nav_more) {
            return R.string.more;
        }

        return R.string.overview;
    }

    private void updateMenu() {
        if (searchItem != null) {
            searchItem.setVisible(currentTab == R.id.nav_transactions);
        }

        if (bookItem != null) {
            bookItem.setVisible(books.size() > 1);
        }
    }

    private void updateSubtitle() {
        toolbar.setSubtitle(server.bookId != null && server.bookName != null ? server.bookName : null);
    }

    private void search(String keyword) {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag("tab" + R.id.nav_transactions);

        if (fragment instanceof TransactionsFragment) {
            ((TransactionsFragment) fragment).setKeyword(keyword);
        }
    }

    private void loadBooks() {
        new Api(server).withoutBook().get("v1/books/list.json", null, Models.Book::parseList, new Api.Callback<List<Models.Book>>() {
            @Override
            public void onSuccess(List<Models.Book> result) {
                books = result;
                serverHasBooks = true;

                // the selected book may have been deleted or the user removed from it
                if (server.bookId != null && findBook(server.bookId) == null) {
                    selectBook(null);
                }

                updateMenu();
            }

            @Override
            public void onError(ApiException error) {
                // servers without the family books feature answer 404; everything else works the same
                serverHasBooks = false;
                updateMenu();
            }
        });
    }

    private Models.Book findBook(String id) {
        for (Models.Book book : books) {
            if (book.id.equals(id)) {
                return book;
            }
        }

        return null;
    }

    private void chooseBook() {
        String[] names = new String[books.size()];
        int checked = 0;

        for (int i = 0; i < books.size(); i++) {
            Models.Book book = books.get(i);
            names[i] = book.isPersonal ? getString(R.string.my_book) : (book.isOwner ? book.name : book.name + " · " + getString(R.string.book_owner, book.ownerNickname));

            if ((server.bookId == null && book.isPersonal) || (server.bookId != null && server.bookId.equals(book.id))) {
                checked = i;
            }
        }

        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_book)
            .setSingleChoiceItems(names, checked, (dialog, which) -> {
                Models.Book book = books.get(which);
                selectBook(book.isPersonal ? null : book);
                dialog.dismiss();
            })
            .show();
    }

    private void selectBook(Models.Book book) {
        server.bookId = book == null ? null : book.id;
        server.bookName = book == null ? null : book.name;
        ServerStore.get(this).update(server);
        updateSubtitle();
        Ui.dataChanged();

        Fragment fragment = getSupportFragmentManager().findFragmentByTag("tab" + currentTab);

        if (fragment instanceof Reloadable) {
            ((Reloadable) fragment).reloadIfStale();
        }
    }

    @Override
    protected void onDestroy() {
        for (WeakReference<Activity> reference : new ArrayList<>(OPEN)) {
            if (reference.get() == null || reference.get() == this) {
                OPEN.remove(reference);
            }
        }

        super.onDestroy();
    }
}
