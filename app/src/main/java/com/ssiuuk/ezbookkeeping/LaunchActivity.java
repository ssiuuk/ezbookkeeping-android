package com.ssiuuk.ezbookkeeping;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.ssiuuk.ezbookkeeping.data.Models;
import com.ssiuuk.ezbookkeeping.data.Server;
import com.ssiuuk.ezbookkeeping.data.ServerStore;

/** Opens the right first screen: the server picker, sign-in, or the app itself. */
public class LaunchActivity extends Activity {
    public static final String ACTION_CHANGE_SERVER = "com.ssiuuk.ezbookkeeping.CHANGE_SERVER";
    public static final String ACTION_ADD_TRANSACTION = "com.ssiuuk.ezbookkeeping.ADD_TRANSACTION";
    public static final String ACTION_EDIT_TRANSACTION = "com.ssiuuk.ezbookkeeping.EDIT_TRANSACTION";
    /** With ACTION_ADD_TRANSACTION: the type of the new transaction, Models.TRANSACTION_EXPENSE when missing. */
    public static final String EXTRA_TYPE = "type";
    /** With ACTION_EDIT_TRANSACTION: the transaction to open. */
    public static final String EXTRA_TRANSACTION_ID = "transaction_id";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        Intent next = nextScreen(this, ACTION_CHANGE_SERVER.equals(intent.getAction()));
        boolean signedIn = next.getComponent() != null && MainActivity.class.getName().equals(next.getComponent().getClassName());
        String transactionId = intent.getStringExtra(EXTRA_TRANSACTION_ID);

        if (signedIn && ACTION_ADD_TRANSACTION.equals(intent.getAction())) {
            startActivities(new Intent[] { next, TransactionEditActivity.addIntent(this, intent.getIntExtra(EXTRA_TYPE, Models.TRANSACTION_EXPENSE)) });
        } else if (signedIn && ACTION_EDIT_TRANSACTION.equals(intent.getAction()) && transactionId != null) {
            startActivities(new Intent[] { next, TransactionEditActivity.editIntent(this, transactionId) });
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
