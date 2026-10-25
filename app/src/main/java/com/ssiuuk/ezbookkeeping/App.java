package com.ssiuuk.ezbookkeeping;

import android.app.Application;
import android.content.Intent;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatDelegate;

import com.ssiuuk.ezbookkeeping.data.Api;
import com.ssiuuk.ezbookkeeping.data.Languages;
import com.ssiuuk.ezbookkeeping.data.Server;
import com.ssiuuk.ezbookkeeping.data.ServerStore;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ServerStore store = ServerStore.get(this);
        AppCompatDelegate.setDefaultNightMode(store.getThemeMode());

        Api.init(this);
        Api.setUnauthorizedListener(this::onSignInExpired);
        ServerStore.setChangeListener(() -> SpendingWidget.refreshAll(this));
    }

    private void onSignInExpired(Server server) {
        ServerStore store = ServerStore.get(this);

        if (!server.isSignedIn() || server != store.getActiveServer()) {
            return;
        }

        store.signOut(server);
        Toast.makeText(this, Languages.localized(this).getString(R.string.sign_in_needed), Toast.LENGTH_LONG).show();

        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }
}
