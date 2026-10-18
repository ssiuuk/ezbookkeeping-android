package com.ssiuuk.ezbookkeeping;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.ssiuuk.ezbookkeeping.data.Api;
import com.ssiuuk.ezbookkeeping.data.ApiException;
import com.ssiuuk.ezbookkeeping.data.Server;
import com.ssiuuk.ezbookkeeping.data.ServerStore;

import org.json.JSONObject;

/** Native sign-in with username and password, including the two-factor step. */
public class LoginActivity extends BaseActivity {
    private Server server;
    private TextInputLayout loginNameLayout;
    private TextInputLayout passwordLayout;
    private TextInputLayout passcodeLayout;
    private TextInputEditText loginNameInput;
    private TextInputEditText passwordInput;
    private TextInputEditText passcodeInput;
    private MaterialButton signInButton;
    private LinearProgressIndicator progress;
    private View passwordStep;
    private View twoFactorStep;
    private String twoFactorToken;
    private boolean internalAuth = true;

    private final ActivityResultLauncher<Intent> webSignIn = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
            String token = result.getData().getStringExtra(WebActivity.EXTRA_TOKEN);
            String user = result.getData().getStringExtra(WebActivity.EXTRA_USER);

            if (token != null) {
                finishSignIn(token, user);
            }
        }
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        server = ServerStore.get(this).getActiveServer();

        if (server == null) {
            startActivity(new Intent(this, ServersActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_login);
        padForSystemBars(findViewById(R.id.scroll), true, true);

        loginNameLayout = findViewById(R.id.login_name_layout);
        passwordLayout = findViewById(R.id.password_layout);
        passcodeLayout = findViewById(R.id.passcode_layout);
        loginNameInput = findViewById(R.id.login_name);
        passwordInput = findViewById(R.id.password);
        passcodeInput = findViewById(R.id.passcode);
        signInButton = findViewById(R.id.sign_in_button);
        progress = findViewById(R.id.progress);
        passwordStep = findViewById(R.id.password_step);
        twoFactorStep = findViewById(R.id.two_factor_step);

        ((TextView) findViewById(R.id.title)).setText(getString(R.string.sign_in_to, server.getDisplayName()));

        Chip serverChip = findViewById(R.id.server_chip);
        serverChip.setText(server.getHostLabel());
        View.OnClickListener changeServer = view -> {
            startActivity(new Intent(this, ServersActivity.class));
        };
        serverChip.setOnClickListener(changeServer);
        serverChip.setOnCloseIconClickListener(changeServer);

        signInButton.setOnClickListener(view -> submit());
        TextView.OnEditorActionListener submitOnDone = (view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                submit();
                return true;
            }

            return false;
        };
        passwordInput.setOnEditorActionListener(submitOnDone);
        passcodeInput.setOnEditorActionListener(submitOnDone);

        findViewById(R.id.web_sign_in_button).setOnClickListener(view ->
            webSignIn.launch(WebActivity.signInIntent(this)));

        String lastLoginName = server.getUser().optString("username", "");

        if (!lastLoginName.isEmpty()) {
            loginNameInput.setText(lastLoginName);
            passwordInput.requestFocus();
        }

        Api.checkServer(server.url, new Api.Callback<Api.ServerInfo>() {
            @Override
            public void onSuccess(Api.ServerInfo info) {
                internalAuth = info.internalAuth;

                if (!internalAuth) {
                    passwordStep.setVisibility(View.GONE);
                    signInButton.setVisibility(View.GONE);
                    showMessage(getString(R.string.internal_auth_disabled));
                }
            }

            @Override
            public void onError(ApiException error) {
                // the sign-in itself reports a server that can't be reached
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Server active = ServerStore.get(this).getActiveServer();

        if (active != server) {
            // the user picked another server meanwhile
            startActivity(LaunchActivity.nextScreen(this, false));
            finish();
        }
    }

    private void submit() {
        if (twoFactorToken != null) {
            submitPasscode();
            return;
        }

        String loginName = text(loginNameInput);
        String password = passwordInput.getText() == null ? "" : passwordInput.getText().toString();
        loginNameLayout.setError(loginName.isEmpty() ? getString(R.string.username_or_email) : null);
        passwordLayout.setError(password.isEmpty() ? getString(R.string.password) : null);

        if (loginName.isEmpty() || password.isEmpty()) {
            return;
        }

        JSONObject body = new JSONObject();

        try {
            body.put("loginName", loginName);
            body.put("password", password);
        } catch (Exception ex) {
            return;
        }

        setBusy(true);
        new Api(server).withToken("").post("authorize.json", body, result -> (JSONObject) result, new Api.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject result) {
                setBusy(false);

                if (result.optBoolean("need2FA")) {
                    twoFactorToken = result.optString("token");
                    passwordStep.setVisibility(View.GONE);
                    twoFactorStep.setVisibility(View.VISIBLE);
                    signInButton.setText(R.string.verify);
                    passcodeInput.requestFocus();
                    return;
                }

                JSONObject user = result.optJSONObject("user");
                finishSignIn(result.optString("token"), user == null ? null : user.toString());
            }

            @Override
            public void onError(ApiException error) {
                setBusy(false);
                passwordLayout.setError(error.getMessage());
            }
        });
    }

    private void submitPasscode() {
        String passcode = text(passcodeInput);

        if (passcode.length() != 6) {
            passcodeLayout.setError(getString(R.string.two_factor_description));
            return;
        }

        JSONObject body = new JSONObject();

        try {
            body.put("passcode", passcode);
        } catch (Exception ex) {
            return;
        }

        setBusy(true);
        new Api(server).withToken(twoFactorToken).post("2fa/authorize.json", body, result -> (JSONObject) result, new Api.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject result) {
                setBusy(false);
                JSONObject user = result.optJSONObject("user");
                finishSignIn(result.optString("token"), user == null ? null : user.toString());
            }

            @Override
            public void onError(ApiException error) {
                setBusy(false);
                passcodeLayout.setError(error.getMessage());
            }
        });
    }

    private void finishSignIn(String token, String userJson) {
        server.token = token;
        server.userJson = userJson;
        server.bookId = null;
        server.bookName = null;
        ServerStore.get(this).update(server);

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.INVISIBLE);
        signInButton.setEnabled(!busy);
    }

    private static String text(TextInputEditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }
}
