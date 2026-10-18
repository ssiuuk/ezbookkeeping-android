package com.ssiuuk.ezbookkeeping;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.PopupMenu;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.ssiuuk.ezbookkeeping.data.Api;
import com.ssiuuk.ezbookkeeping.data.ApiException;
import com.ssiuuk.ezbookkeeping.data.Server;
import com.ssiuuk.ezbookkeeping.data.ServerStore;

import java.util.List;

/** Lists the saved servers, switches between them and adds new ones. Also the first screen on a fresh install. */
public class ServersActivity extends BaseActivity {
    private ServerStore store;
    private LinearLayout serverList;
    private View addForm;
    private MaterialButton showAddFormButton;
    private TextInputLayout addressLayout;
    private TextInputEditText addressInput;
    private TextInputEditText nameInput;
    private MaterialButton connectButton;
    private CircularProgressIndicator connectProgress;
    private boolean firstRun;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_servers);
        store = ServerStore.get(this);
        firstRun = store.getServers().isEmpty();

        setupToolbar(firstRun ? 0 : R.string.servers, !firstRun);

        if (firstRun) {
            findViewById(R.id.app_bar).setVisibility(View.INVISIBLE);
            findViewById(R.id.welcome).setVisibility(View.VISIBLE);
        }

        padForSystemBars(findViewById(R.id.scroll), false, true);

        serverList = findViewById(R.id.server_list);
        addForm = findViewById(R.id.add_form);
        showAddFormButton = findViewById(R.id.show_add_form);
        addressLayout = findViewById(R.id.server_address_layout);
        addressInput = findViewById(R.id.server_address);
        nameInput = findViewById(R.id.server_name);
        connectButton = findViewById(R.id.connect_button);
        connectProgress = findViewById(R.id.connect_progress);

        showAddFormButton.setOnClickListener(view -> showAddForm(true));
        findViewById(R.id.cancel_add).setOnClickListener(view -> showAddForm(false));
        connectButton.setOnClickListener(view -> connect());
        nameInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                connect();
                return true;
            }

            return false;
        });

        if (firstRun) {
            showAddForm(true);
            findViewById(R.id.cancel_add).setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderServers();
    }

    private void showAddForm(boolean show) {
        addForm.setVisibility(show ? View.VISIBLE : View.GONE);
        showAddFormButton.setVisibility(show ? View.GONE : View.VISIBLE);
        addressLayout.setError(null);

        if (show) {
            addressInput.requestFocus();
        }
    }

    private void renderServers() {
        serverList.removeAllViews();
        List<Server> servers = store.getServers();
        Server active = store.getActiveServer();
        LayoutInflater inflater = LayoutInflater.from(this);

        for (Server server : servers) {
            MaterialCardView card = (MaterialCardView) inflater.inflate(R.layout.item_server, serverList, false);
            ((TextView) card.findViewById(R.id.name)).setText(server.getDisplayName());
            ((TextView) card.findViewById(R.id.address)).setText(server.url);

            boolean isActive = active != null && active.id.equals(server.id);
            String status = server.isSignedIn() ? getString(R.string.server_signed_in_as, server.getUserDisplayName()) : getString(R.string.server_not_signed_in);
            ((TextView) card.findViewById(R.id.status)).setText(isActive ? getString(R.string.server_in_use) + " · " + status : status);

            if (isActive) {
                card.setStrokeColor(ColorStateList.valueOf(MaterialColors.getColor(card, androidx.appcompat.R.attr.colorPrimary)));
                card.setStrokeWidth(getResources().getDimensionPixelSize(R.dimen.active_stroke));
            }

            card.setOnClickListener(view -> open(server));
            View menuButton = card.findViewById(R.id.menu);
            menuButton.setOnClickListener(view -> showServerMenu(menuButton, server));
            serverList.addView(card);
        }
    }

    private void showServerMenu(View anchor, Server server) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.rename);

        if (server.isSignedIn()) {
            menu.getMenu().add(0, 2, 1, R.string.sign_out);
        }

        menu.getMenu().add(0, 3, 2, R.string.remove);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                rename(server);
            } else if (item.getItemId() == 2) {
                SettingsActivity.confirmSignOut(this, server, this::renderServers);
            } else if (item.getItemId() == 3) {
                confirmRemove(server);
            }

            return true;
        });
        menu.show();
    }

    private void rename(Server server) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setSingleLine(true);
        input.setText(server.name);
        input.setSelection(input.getText().length());
        input.setHint(server.getHostLabel());

        FrameLayout container = new FrameLayout(this);
        int padding = getResources().getDimensionPixelSize(R.dimen.dialog_padding);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(input);

        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, (dialog, which) -> {
                server.name = input.getText().toString().trim();
                store.update(server);
                renderServers();
            })
            .show();
    }

    private void confirmRemove(Server server) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remove_server_title)
            .setMessage(getString(R.string.remove_server_message, server.getDisplayName()))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove, (dialog, which) -> {
                boolean wasActive = server == store.getActiveServer();
                store.remove(server);
                WebSession.clear(this, server);

                if (store.getServers().isEmpty()) {
                    restart();
                    return;
                }

                if (wasActive) {
                    MainActivity.finishAll(this);
                }

                renderServers();
            })
            .show();
    }

    private void connect() {
        String url = ServerStore.normalizeServerUrl(addressInput.getText() == null ? "" : addressInput.getText().toString());

        if (url == null) {
            addressLayout.setError(getString(R.string.invalid_server_address));
            return;
        }

        addressLayout.setError(null);
        setConnecting(true);

        Api.checkServer(url, new Api.Callback<Api.ServerInfo>() {
            @Override
            public void onSuccess(Api.ServerInfo info) {
                setConnecting(false);
                String name = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
                Server server = store.add(name, url);
                addressInput.setText("");
                nameInput.setText("");
                showAddForm(false);
                open(server);
            }

            @Override
            public void onError(ApiException error) {
                setConnecting(false);
                addressLayout.setError(error.getMessage() == null ? getString(R.string.not_ezbookkeeping_server) : error.getMessage());
            }
        });
    }

    private void setConnecting(boolean connecting) {
        connectProgress.setVisibility(connecting ? View.VISIBLE : View.GONE);
        connectButton.setEnabled(!connecting);
    }

    private void open(Server server) {
        Server previous = store.getActiveServer();
        store.setActive(server);

        if (previous != null && previous != server) {
            MainActivity.finishAll(this);
        }

        Intent intent = new Intent(this, server.isSignedIn() ? MainActivity.class : LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    private void restart() {
        Intent intent = new Intent(this, ServersActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
