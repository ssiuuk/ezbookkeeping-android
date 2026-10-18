package com.ssiuuk.ezbookkeeping;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.ssiuuk.ezbookkeeping.data.Server;
import com.ssiuuk.ezbookkeeping.data.ServerStore;

/** Opens the right first screen: the server picker, sign-in, or the app itself. */
public class LaunchActivity extends Activity {
    public static final String ACTION_CHANGE_SERVER = "com.ssiuuk.ezbookkeeping.CHANGE_SERVER";
    public static final String ACTION_ADD_TRANSACTION = "com.ssiuuk.ezbookkeeping.ADD_TRANSACTION";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent next = nextScreen(this, ACTION_CHANGE_SERVER.equals(getIntent().getAction()));

        if (ACTION_ADD_TRANSACTION.equals(getIntent().getAction()) && next.getComponent() != null
            && MainActivity.class.getName().equals(next.getComponent().getClassName())) {
            startActivities(new Intent[] { next, TransactionEditActivity.addIntent(this, null) });
        } else {
            startActivity(next);
        }

        finish();
    }

    static Intent nextScreen(Activity activity, boolean chooseServer) {
        Server server = ServerStore.get(activity).getActiveServer();

        if (chooseServer || server == null) {
            return new Intent(activity, ServersActivity.class);
        }

        if (!server.isSignedIn()) {
            return new Intent(activity, LoginActivity.class);
        }

        return new Intent(activity, MainActivity.class);
    }
}
