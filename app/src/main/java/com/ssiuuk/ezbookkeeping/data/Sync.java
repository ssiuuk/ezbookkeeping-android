package com.ssiuuk.ezbookkeeping.data;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkerParameters;

import com.ssiuuk.ezbookkeeping.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Sends the transactions added, changed or deleted while the server was out of reach, oldest first, once it answers again.
 * A change to a transaction that was also changed on the server meanwhile is held back as a conflict for the user to settle.
 */
public final class Sync {
    private static final String WORK_NAME = "sync";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // the server's error for a transaction that doesn't exist (any more)
    private static final int ERROR_TRANSACTION_NOT_FOUND = 205001;
    // the server may move a transaction's time by a few seconds when another one has the same second
    private static final long SAME_TIME_SECONDS = 120;
    private static final String[] COMPARED = { "type", "categoryId", "time", "sourceAccountId", "sourceAmount", "comment", "merchant", "projectId" };

    private static Runnable listener;

    private Sync() {
    }

    /** Called on the main thread after changes were sent or held back, so the screens can show the server's figures again. */
    public static void setListener(Runnable changeListener) {
        listener = changeListener;
    }

    /** Sends what is waiting as soon as there is a network, and keeps trying while the server doesn't answer. */
    public static void schedule(Context context) {
        if (!hasWaiting(context)) {
            return;
        }

        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SyncWorker.class)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request);
    }

    /** Whether the phone has a network at all; without one, saving goes straight to the phone. */
    public static boolean hasNetwork(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (manager == null) {
            return true;
        }

        Network network = manager.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static boolean hasWaiting(Context context) {
        for (LocalStore.Change change : LocalStore.get(context).allChanges()) {
            if (change.isWaiting()) {
                return true;
            }
        }

        return false;
    }

    /** Runs the sending in the background, also when the app isn't open. */
    public static final class SyncWorker extends androidx.work.Worker {
        public SyncWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
            super(context, parameters);
        }

        @NonNull
        @Override
        public Result doWork() {
            return run(getApplicationContext()) ? Result.success() : Result.retry();
        }
    }

    /**
     * Saves a transaction added, changed or deleted while the server couldn't be reached. A later change to the same transaction
     * replaces the earlier one but keeps the server's version it started from.
     */
    public static LocalStore.Change queue(Context context, Server server, String bookId, String kind, String transactionId,
                                          JSONObject body, JSONObject base, JSONObject labels) {
        LocalStore store = LocalStore.get(context);
        LocalStore.Change change = transactionId == null ? null : store.changeFor(server.id, transactionId);

        if (change == null) {
            change = new LocalStore.Change();
            change.serverId = server.id;
            change.transactionId = transactionId;
            change.base = base;
            change.createdAt = System.currentTimeMillis();
        }

        change.bookId = bookId;
        change.kind = kind;
        change.body = body;
        change.labels = labels == null ? new JSONObject() : labels;
        change.state = LocalStore.STATE_WAITING;
        change.message = null;
        change.serverCopy = null;
        store.save(change);
        schedule(context);
        return change;
    }

    /** Settles a conflict, or a change the server refused, with the phone's version: it is sent again. */
    public static void keepMine(Context context, LocalStore.Change change) {
        if (LocalStore.STATE_CONFLICT.equals(change.state)) {
            if (change.serverCopy == null && LocalStore.KIND_MODIFY.equals(change.kind)) {
                // deleted on the server: add it back as a new transaction
                change.kind = LocalStore.KIND_ADD;
                change.transactionId = null;
                change.base = null;
                change.body.remove("id");

                try {
                    change.body.put("clientSessionId", UUID.randomUUID().toString());
                } catch (JSONException ex) {
                    return;
                }
            } else {
                // what the server has now is what the phone's version replaces
                change.base = change.serverCopy;
            }
        }

        change.state = LocalStore.STATE_WAITING;
        change.serverCopy = null;
        change.message = null;
        change.attempts = 0;
        LocalStore.get(context).save(change);
        schedule(context);
    }

    /** Settles a conflict with the server's version, or gives up a change the server refused. */
    public static void keepServers(Context context, LocalStore.Change change) {
        LocalStore.get(context).remove(change.id);
        notifyListener();
    }

    /** Sends everything waiting; false when a server couldn't be reached, so it should be tried again later. */
    public static synchronized boolean run(Context context) {
        LocalStore store = LocalStore.get(context);
        ServerStore servers = ServerStore.get(context);
        Set<String> skipped = new HashSet<>();
        boolean complete = true;
        boolean changed = false;

        for (LocalStore.Change change : store.allChanges()) {
            if (!change.isWaiting() || skipped.contains(change.serverId)) {
                continue;
            }

            Server server = servers.findById(change.serverId);

            if (server == null) {
                // the server was removed from the app
                store.remove(change.id);
                continue;
            }

            if (!server.isSignedIn()) {
                // waits until the user signs in again
                skipped.add(server.id);
                continue;
            }

            try {
                send(store, server, change);
                changed = true;
            } catch (ApiException ex) {
                if (ex.unreachable) {
                    // later changes may depend on this one, so the rest of this server's changes wait too
                    skipped.add(server.id);
                    complete = false;
                } else if (ex.isUnauthorized()) {
                    skipped.add(server.id);
                } else {
                    change.state = LocalStore.STATE_FAILED;
                    change.message = ex.getMessage();
                    store.save(change);
                    changed = true;
                }
            } catch (JSONException ex) {
                change.state = LocalStore.STATE_FAILED;
                change.message = Languages.localized(context).getString(R.string.error_unexpected_response);
                store.save(change);
                changed = true;
            }
        }

        if (changed) {
            notifyListener();
        }

        return complete;
    }

    private static void notifyListener() {
        if (listener != null) {
            MAIN.post(listener);
        }
    }

    private static void send(LocalStore store, Server server, LocalStore.Change change) throws ApiException, JSONException {
        Api api = new Api(server).inBackground().withBook(change.bookId);

        switch (change.kind) {
            case LocalStore.KIND_ADD:
                // an earlier try may have reached the server even though no answer came back
                if (change.attempts > 0 && alreadyAdded(api, change.body)) {
                    store.remove(change.id);
                    return;
                }

                change.attempts++;
                store.save(change);
                api.call("POST", "v1/transactions/add.json", null, change.body);
                store.remove(change.id);
                return;

            case LocalStore.KIND_MODIFY: {
                JSONObject current = fetch(api, change.transactionId);

                if (current == null) {
                    conflict(store, change, null);
                } else if (same(current, change.body)) {
                    store.remove(change.id);
                } else if (change.base != null && !same(current, change.base)) {
                    conflict(store, change, current);
                } else {
                    api.call("POST", "v1/transactions/modify.json", null, change.body);
                    store.remove(change.id);
                }

                return;
            }

            case LocalStore.KIND_DELETE: {
                JSONObject current = fetch(api, change.transactionId);

                if (current == null) {
                    // already gone
                    store.remove(change.id);
                } else if (change.base != null && !same(current, change.base)) {
                    conflict(store, change, current);
                } else {
                    JSONObject body = new JSONObject();
                    body.put("id", change.transactionId);
                    api.call("POST", "v1/transactions/delete.json", null, body);
                    store.remove(change.id);
                }

                return;
            }

            default:
                store.remove(change.id);
        }
    }

    private static void conflict(LocalStore store, LocalStore.Change change, JSONObject serverCopy) {
        change.state = LocalStore.STATE_CONFLICT;
        change.serverCopy = serverCopy;
        change.message = null;
        store.save(change);
    }

    /** The server's version of a transaction, or null when it no longer has it. */
    private static JSONObject fetch(Api api, String transactionId) throws ApiException, JSONException {
        try {
            Object result = api.call("GET", "v1/transactions/get.json",
                Api.query("id", transactionId, "trim_account", "false", "trim_category", "false", "trim_tag", "false"), null);
            return result instanceof JSONObject ? (JSONObject) result : null;
        } catch (ApiException ex) {
            if (ex.errorCode == ERROR_TRANSACTION_NOT_FOUND) {
                return null;
            }

            throw ex;
        }
    }

    /** Whether the server already has this new transaction: same type, account, category, amount and note, at about the same time. */
    private static boolean alreadyAdded(Api api, JSONObject body) throws ApiException, JSONException {
        long time = body.optLong("time");
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(time * 1000);
        Object result = api.call("GET", "v1/transactions/list/by_month.json", Api.query(
            "year", String.valueOf(calendar.get(Calendar.YEAR)),
            "month", String.valueOf(calendar.get(Calendar.MONTH) + 1),
            "type", body.optString("type"),
            "trim_account", "true", "trim_category", "true", "trim_tag", "true"), null);
        JSONArray items = result instanceof JSONObject ? ((JSONObject) result).optJSONArray("items") : null;

        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);

            if (item != null && Math.abs(item.optLong("time") - time) <= SAME_TIME_SECONDS
                && item.optString("sourceAccountId").equals(body.optString("sourceAccountId"))
                && item.optString("categoryId").equals(body.optString("categoryId"))
                && item.optLong("sourceAmount") == body.optLong("sourceAmount")
                && item.optString("comment", "").equals(body.optString("comment", ""))) {
                return true;
            }
        }

        return false;
    }

    /** Whether two versions of a transaction say the same, in the fields this app shows and edits. */
    public static boolean same(JSONObject a, JSONObject b) {
        for (String key : COMPARED) {
            if (!value(a, key).equals(value(b, key))) {
                return false;
            }
        }

        if (a.optInt("type") == Models.TRANSACTION_TRANSFER
            && (!value(a, "destinationAccountId").equals(value(b, "destinationAccountId"))
                || !value(a, "destinationAmount").equals(value(b, "destinationAmount")))) {
            return false;
        }

        return tagIds(a).equals(tagIds(b));
    }

    /** The fields that differ, in the order the add screen shows them. */
    public static List<String> differences(JSONObject mine, JSONObject servers) {
        List<String> keys = new ArrayList<>();
        String[] order = { "type", "sourceAmount", "destinationAmount", "categoryId", "sourceAccountId", "destinationAccountId", "time", "projectId", "merchant", "comment" };

        for (String key : order) {
            if ((key.startsWith("destination") && mine.optInt("type") != Models.TRANSACTION_TRANSFER)) {
                continue;
            }

            if (!value(mine, key).equals(value(servers, key))) {
                keys.add(key);
            }
        }

        if (!tagIds(mine).equals(tagIds(servers))) {
            keys.add("tagIds");
        }

        return keys;
    }

    private static String value(JSONObject json, String key) {
        String fallback = key.endsWith("Id") || key.endsWith("Amount") ? "0" : "";
        String value = json.optString(key, fallback);
        return value.isEmpty() ? fallback : value.trim();
    }

    private static List<String> tagIds(JSONObject json) {
        List<String> ids = new ArrayList<>();
        JSONArray array = json.optJSONArray("tagIds");

        for (int i = 0; array != null && i < array.length(); i++) {
            ids.add(array.optString(i));
        }

        Collections.sort(ids);
        return ids;
    }
}
