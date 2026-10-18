package com.ssiuuk.ezbookkeeping;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.ssiuuk.ezbookkeeping.data.Api;
import com.ssiuuk.ezbookkeeping.data.ApiException;
import com.ssiuuk.ezbookkeeping.data.Server;
import com.ssiuuk.ezbookkeeping.data.ServerStore;

/** App settings: which server to use, sign-out, Wallet purchases, theme and version. */
public class SettingsActivity extends BaseActivity {
    private LinearLayout container;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setupToolbar(R.string.settings, true);
        container = findViewById(R.id.container);
        padForSystemBars(findViewById(R.id.scroll), false, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        container.removeAllViews();
        ServerStore store = ServerStore.get(this);
        Server server = store.getActiveServer();

        addHeader(R.string.current_server);

        if (server != null) {
            addItem(R.drawable.ic_server, server.getDisplayName(), server.url, view -> startActivity(new Intent(this, ServersActivity.class)));

            if (server.isSignedIn()) {
                addItem(R.drawable.ic_person, getString(R.string.signed_in_account), server.getUserDisplayName(), null);
                addItem(R.drawable.ic_logout, getString(R.string.sign_out), null, view -> confirmSignOut(this, server, null));
            }
        }

        addItem(R.drawable.ic_swap, getString(R.string.manage_servers), getResources().getQuantityString(R.plurals.server_count, store.getServers().size(), store.getServers().size()),
            view -> startActivity(new Intent(this, ServersActivity.class)));

        addHeader(R.string.section_app);
        boolean capturing = PaymentListenerService.hasAccess(this);
        addItem(R.drawable.ic_wallet, getString(R.string.payment_capture), getString(capturing ? R.string.payment_capture_on : R.string.payment_capture_off),
            view -> explainPaymentCapture(capturing));
        addItem(R.drawable.ic_dark_mode, getString(R.string.theme), themeName(store.getThemeMode()), view -> chooseTheme());
        addItem(R.drawable.ic_info, getString(R.string.about), getString(R.string.version, versionName()), null);
    }

    /** Purchases are read from Wallet's notifications, which needs notification access from Android's settings. */
    private void explainPaymentCapture(boolean capturing) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.payment_capture)
            .setMessage(capturing ? R.string.payment_capture_turn_off_message : R.string.payment_capture_message)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.app_info, (dialog, which) -> startActivity(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null))))
            .setPositiveButton(R.string.open_settings, (dialog, which) -> openNotificationAccess())
            .show();
    }

    private void openNotificationAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent detail = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, PaymentListenerService.component(this).flattenToString());

            try {
                startActivity(detail);
                return;
            } catch (ActivityNotFoundException ex) {
                // some phones only have the list of all apps
            }
        }

        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    private void chooseTheme() {
        ServerStore store = ServerStore.get(this);
        int[] modes = { AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES };
        String[] names = { getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark) };
        int checked = 0;

        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == store.getThemeMode()) {
                checked = i;
            }
        }

        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(names, checked, (dialog, which) -> {
                store.setThemeMode(modes[which]);
                dialog.dismiss();
                AppCompatDelegate.setDefaultNightMode(modes[which]);
            })
            .show();
    }

    private String themeName(int mode) {
        if (mode == AppCompatDelegate.MODE_NIGHT_NO) {
            return getString(R.string.theme_light);
        } else if (mode == AppCompatDelegate.MODE_NIGHT_YES) {
            return getString(R.string.theme_dark);
        }

        return getString(R.string.theme_system);
    }

    private String versionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName;
        } catch (PackageManager.NameNotFoundException ex) {
            return "";
        }
    }

    private void addHeader(int titleRes) {
        View header = LayoutInflater.from(this).inflate(R.layout.item_header, container, false);
        ((TextView) header.findViewById(R.id.title)).setText(titleRes);
        container.addView(header);
    }

    private void addItem(int iconRes, String title, String subtitle, View.OnClickListener listener) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_more, container, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);

        if (subtitle != null) {
            TextView subtitleView = row.findViewById(R.id.subtitle);
            subtitleView.setText(subtitle);
            subtitleView.setVisibility(View.VISIBLE);
        }

        if (listener == null) {
            row.setClickable(false);
            row.setBackground(null);
            row.findViewById(R.id.chevron).setVisibility(View.GONE);
        } else {
            row.setOnClickListener(listener);
        }

        container.addView(row);
    }

    /** Signs out of a server after asking; for the server in use, goes back to its sign-in screen. */
    static void confirmSignOut(Activity activity, Server server, Runnable afterwards) {
        new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.sign_out)
            .setMessage(activity.getString(R.string.sign_out_confirm, server.getDisplayName()))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.sign_out, (dialog, which) -> {
                // revoke the token on the server too, but don't wait for it
                new Api(server).withToken(server.token).get("logout.json", null, null, new Api.Callback<Object>() {
                    @Override
                    public void onSuccess(Object result) {
                    }

                    @Override
                    public void onError(ApiException error) {
                    }
                });

                ServerStore store = ServerStore.get(activity);
                boolean active = server == store.getActiveServer();
                store.signOut(server);
                WebSession.clear(activity, server);

                if (afterwards != null) {
                    afterwards.run();
                }

                if (active) {
                    Intent intent = new Intent(activity, LoginActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    activity.startActivity(intent);
                }
            })
            .show();
    }
}
