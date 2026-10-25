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
    // the server recognizes a repeated add by its clientSessionId for 5 minutes by default; a little less to be safe
    private static final long SERVER_REMEMBERS_MILLIS = 4 * 60 * 1000;
    private static final String[] COMPARED = { "type", "categoryId", "sourceAccountId", "sourceAmount", "comment", "merchant", "projectId" };

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

    /** The user a change belongs to: changes are only sent with, and shown to, the user who made them. */
    public static String userOf(Server server) {
        return server.getUser().optString("username", "");
    }

    /** The signed-in user's changes for a server, oldest first. */
    public static List<LocalStore.Change> changes(Context context, Server server) {
        return LocalStore.get(context).changes(server.id, userOf(server));
    }

    /** The signed-in user's change waiting for one of the server's transactions, if any. */
    public static LocalStore.Change changeFor(Context context, Server server, String transactionId) {
        return LocalStore.get(context).changeFor(server.id, userOf(server), transactionId);
    }

    /**
     * Saves a transaction added, changed or deleted while the server couldn't be reached. A later change to the same transaction
     * replaces the earlier one but keeps the server's version it started from.
     */
    public static LocalStore.Change queue(Context context, Server server, String bookId, String kind, String transactionId,
                                          JSONObject body, JSONObject base, JSONObject labels) {
        LocalStore store = LocalStore.get(context);
        LocalStore.Change change = transactionId == null ? null : changeFor(context, server, transactionId);

        if (change == null) {
            change = new LocalStore.Change();
            change.serverId = server.id;
            change.userName = userOf(server);
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

        if (change.id == 0 || !store.update(change)) {
            change.id = 0;
            store.insert(change);
        }

        schedule(context);
        return change;
    }

    /**
     * Saves the user's new version of a change opened in the add screen. If it was sent while the screen was open, the new
     * version becomes a change to the transaction the server made of it, so nothing typed is lost and nothing is added twice.
     */
    public static LocalStore.Change saveEdit(Context context, Server server, LocalStore.Change change, JSONObject body, JSONObject labels)
        throws JSONException {
        LocalStore store = LocalStore.get(context);
        change.body = body;
        change.labels = labels;
        change.state = LocalStore.STATE_WAITING;
        change.message = null;
        change.serverCopy = null;
        change.attempts = 0;

        if (store.update(change)) {
            schedule(context);
            return change;
        }

        LocalStore.Sent sent = store.sent(change.id);

        if (sent != null && sent.serverCopy != null) {
            // sent while the screen was open: the edit changes the transaction the server has
            body.remove("clientSessionId");
            body.put("id", sent.transactionId);
            return queue(context, server, change.bookId, LocalStore.KIND_MODIFY, sent.transactionId, body, sent.serverCopy, labels);
        }

        if (sent != null) {
            // it turned out to be deleted on the server: the edit is added back
            body.remove("id");
            body.put("clientSessionId", UUID.randomUUID().toString());
            return queue(context, server, change.bookId, LocalStore.KIND_ADD, null, body, null, labels);
        }

        // settled meanwhile by keeping the server's version, but the user saved theirs again, so theirs is sent
        return queue(context, server, change.bookId, change.kind, change.transactionId, body, change.base, labels);
    }

    /** Deletes a change opened in the add screen: an add is dropped, anything else is deleted on the server once it answers. */
    public static void deleteEdit(Context context, Server server, LocalStore.Change change) throws JSONException {
        LocalStore store = LocalStore.get(context);
        String transactionId = change.transactionId;
        JSONObject base = change.base;

        if (store.change(change.id) == null) {
            // sent while the screen was open: delete what the server made of it
            LocalStore.Sent sent = store.sent(change.id);

            if (sent == null || sent.transactionId == null) {
                return;
            }

            transactionId = sent.transactionId;
            base = sent.serverCopy;
        } else if (LocalStore.KIND_ADD.equals(change.kind)) {
            store.remove(change.id);
            // if it was on its way meanwhile, sending notices it is gone and deletes it again
            return;
        }

        store.remove(change.id);
        JSONObject body = new JSONObject().put("id", transactionId);
        queue(context, server, change.bookId, LocalStore.KIND_DELETE, transactionId, body, base, change.labels);
    }

    /** Settles a conflict, or a change the server refused, with the phone's version: it is sent again. */
    public static void keepMine(Context context, LocalStore.Change change) {
        if (LocalStore.STATE_CONFLICT.equals(change.state)) {
            if (LocalStore.KIND_ADD.equals(change.kind) || (change.serverCopy == null && LocalStore.KIND_MODIFY.equals(change.kind))) {
                // added anyway, as a new transaction; or deleted on the server and added back
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
        change.lastAttemptAt = 0;
        LocalStore.get(context).update(change);
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
                // removing a server removes its changes, so this one isn't in the app (yet)
                continue;
            }

            if (!server.isSignedIn()) {
                // waits until the user signs in again
                skipped.add(server.id);
                continue;
            }

            if (!userOf(server).equals(change.userName)) {
                // made by another user of this server: waits until they sign in again
                continue;
            }

            Remote remote = new Api(server).inBackground().withBook(change.bookId)::call;

            try {
                send(store, change, remote, System.currentTimeMillis());
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
                    store.updateIfUnchanged(change);
                    changed = true;
                }
            } catch (JSONException ex) {
                change.state = LocalStore.STATE_FAILED;
                change.message = Languages.localized(context).getString(R.string.error_unexpected_response);
                store.updateIfUnchanged(change);
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

    /** How a change reaches the server; the app's Api, or a stand-in in tests. */
    interface Remote {
        Object call(String method, String path, java.util.Map<String, String> query, JSONObject body) throws ApiException, JSONException;
    }

    /** Sends one change. Every write checks that the user didn't edit it meanwhile, since the add screen may be open on it. */
    static void send(LocalStore store, LocalStore.Change change, Remote remote, long now) throws ApiException, JSONException {
        switch (change.kind) {
            case LocalStore.KIND_ADD: {
                // an earlier try may have reached the server even though no answer came back. Within the server's own
                // duplicate check the same clientSessionId gets that transaction back; after it, a transaction like this one
                // may be it or a separate purchase, so the user decides instead of the app.
                if (change.attempts > 0 && now - change.lastAttemptAt > SERVER_REMEMBERS_MILLIS) {
                    JSONObject similar = findSimilar(remote, change.body);

                    if (similar != null) {
                        conflict(store, change, similar);
                        return;
                    }
                }

                change.attempts++;
                change.lastAttemptAt = now;

                if (!store.updateIfUnchanged(change)) {
                    // edited meanwhile; the newer version goes in the next round
                    return;
                }

                Object result = remote.call("POST", "v1/transactions/add.json", null, change.body);
                JSONObject added = result instanceof JSONObject ? (JSONObject) result : null;
                String transactionId = added == null ? null : added.optString("id", null);
                store.markSent(change, transactionId, added);

                if (!store.removeIfUnchanged(change) && transactionId != null) {
                    LocalStore.Change latest = store.change(change.id);

                    if (latest == null) {
                        // deleted on the phone while it was on its way: delete it on the server too
                        latest = new LocalStore.Change();
                        latest.serverId = change.serverId;
                        latest.userName = change.userName;
                        latest.bookId = change.bookId;
                        latest.kind = LocalStore.KIND_DELETE;
                        latest.transactionId = transactionId;
                        latest.body = new JSONObject().put("id", transactionId);
                        latest.base = added;
                        latest.labels = change.labels;
                        latest.createdAt = now;
                        store.insert(latest);
                    } else {
                        // edited while it was on its way: the newer version changes the transaction the server just made
                        latest.kind = LocalStore.KIND_MODIFY;
                        latest.transactionId = transactionId;
                        latest.base = added;
                        latest.body.remove("clientSessionId");
                        latest.body.put("id", transactionId);
                        latest.attempts = 0;
                        store.updateIfUnchanged(latest);
                    }
                }

                return;
            }

            case LocalStore.KIND_MODIFY: {
                JSONObject current = fetch(remote, change.transactionId);

                if (current == null) {
                    conflict(store, change, null);
                } else if (same(current, change.body)) {
                    store.markSent(change, change.transactionId, current);
                    store.removeIfUnchanged(change);
                } else if (change.base != null && !same(current, change.base)) {
                    conflict(store, change, current);
                } else {
                    Object result = remote.call("POST", "v1/transactions/modify.json", null, change.body);
                    JSONObject modified = result instanceof JSONObject ? (JSONObject) result : null;
                    store.markSent(change, change.transactionId, modified);

                    if (!store.removeIfUnchanged(change)) {
                        // edited while it was on its way: the newer version changes what the server has now
                        LocalStore.Change latest = store.change(change.id);

                        if (latest != null && modified != null) {
                            latest.base = modified;
                            store.updateIfUnchanged(latest);
                        }
                    }
                }

                return;
            }

            case LocalStore.KIND_DELETE: {
                JSONObject current = fetch(remote, change.transactionId);

                if (current == null) {
                    // already gone
                    store.markSent(change, change.transactionId, null);
                    store.removeIfUnchanged(change);
                } else if (change.base != null && !same(current, change.base)) {
                    conflict(store, change, current);
                } else {
                    remote.call("POST", "v1/transactions/delete.json", null, new JSONObject().put("id", change.transactionId));
                    store.markSent(change, change.transactionId, null);
                    store.removeIfUnchanged(change);
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
        store.updateIfUnchanged(change);
    }

    /** The server's version of a transaction, or null when it no longer has it. */
    private static JSONObject fetch(Remote remote, String transactionId) throws ApiException, JSONException {
        try {
            Object result = remote.call("GET", "v1/transactions/get.json",
                Api.query("id", transactionId, "trim_account", "false", "trim_category", "false", "trim_tag", "false"), null);
            return result instanceof JSONObject ? (JSONObject) result : null;
        } catch (ApiException ex) {
            if (ex.errorCode == ERROR_TRANSACTION_NOT_FOUND) {
                return null;
            }

            throw ex;
        }
    }

    /** A transaction on the server that says the same as this new one, at about the same time, or null. */
    private static JSONObject findSimilar(Remote remote, JSONObject body) throws ApiException, JSONException {
        long time = body.optLong("time");
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(time * 1000);
        Object result = remote.call("GET", "v1/transactions/list/by_month.json", Api.query(
            "year", String.valueOf(calendar.get(Calendar.YEAR)),
            "month", String.valueOf(calendar.get(Calendar.MONTH) + 1),
            "type", body.optString("type"),
            "trim_account", "true", "trim_category", "true", "trim_tag", "true"), null);
        JSONArray items = result instanceof JSONObject ? ((JSONObject) result).optJSONArray("items") : null;

        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);

            if (item != null && Math.abs(item.optLong("time") - time) <= SAME_TIME_SECONDS && same(item, body, false)) {
                return item;
            }
        }

        return null;
    }

    /**
     * Whether two versions of a transaction say the same: what this app shows and edits, and what it sends without showing
     * (receipts, hidden amount, time zone and location). Either may be the server's JSON or what add or modify.json is sent.
     */
    public static boolean same(JSONObject a, JSONObject b) {
        return same(a, b, true);
    }

    private static boolean same(JSONObject a, JSONObject b, boolean exactTime) {
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

        if (!tagIds(a).equals(tagIds(b))) {
            return false;
        }

        if (!exactTime) {
            // a list of the month only has the fields above
            return true;
        }

        return value(a, "time").equals(value(b, "time"))
            && pictureIds(a).equals(pictureIds(b))
            && a.optBoolean("hideAmount") == b.optBoolean("hideAmount")
            && a.optInt("utcOffset") == b.optInt("utcOffset")
            && geoLocation(a).equals(geoLocation(b));
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

        if (!pictureIds(mine).equals(pictureIds(servers))) {
            keys.add("pictureIds");
        }

        if (mine.optBoolean("hideAmount") != servers.optBoolean("hideAmount") || !geoLocation(mine).equals(geoLocation(servers))) {
            keys.add("other");
        }

        return keys;
    }

    private static String value(JSONObject json, String key) {
        String fallback = key.endsWith("Id") || key.endsWith("Amount") ? "0" : "";
        String value = json.optString(key, fallback);
        return value.isEmpty() ? fallback : value.trim();
    }

    /** Receipt ids, from what is sent ("pictureIds") or from the server's "pictures". */
    private static List<String> pictureIds(JSONObject json) {
        List<String> ids = new ArrayList<>();
        JSONArray sentIds = json.optJSONArray("pictureIds");
        JSONArray pictures = json.optJSONArray("pictures");

        for (int i = 0; sentIds != null && i < sentIds.length(); i++) {
            ids.add(sentIds.optString(i));
        }

        for (int i = 0; sentIds == null && pictures != null && i < pictures.length(); i++) {
            JSONObject picture = pictures.optJSONObject(i);

            if (picture != null) {
                ids.add(picture.optString("pictureId"));
            }
        }

        Collections.sort(ids);
        return ids;
    }

    private static String geoLocation(JSONObject json) {
        JSONObject location = json.optJSONObject("geoLocation");
        return location == null ? "" : location.optDouble("latitude", 0) + "," + location.optDouble("longitude", 0);
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
