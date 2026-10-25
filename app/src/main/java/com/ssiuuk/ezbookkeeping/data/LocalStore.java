package com.ssiuuk.ezbookkeeping.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The phone's own database: what each server last answered, to show while it can't be reached,
 * and the changes made meanwhile, waiting to be sent to it.
 */
public final class LocalStore extends SQLiteOpenHelper {
    private static final String NAME = "local.db";
    private static final int VERSION = 2;
    // answers kept per server; the oldest go first
    private static final int MAX_CACHED = 400;
    // how long the server's id for a sent change is kept, for an editor that was still open on it
    private static final long SENT_KEPT_MILLIS = 7L * 24 * 60 * 60 * 1000;

    public static final String KIND_ADD = "add";
    public static final String KIND_MODIFY = "modify";
    public static final String KIND_DELETE = "delete";

    /** Waiting to be sent. */
    public static final String STATE_WAITING = "waiting";
    /**
     * Changed on the server too, and the user picks which version stays. For an add, the server has a transaction like it,
     * which may be this one sent earlier without an answer coming back.
     */
    public static final String STATE_CONFLICT = "conflict";
    /** The server refused it, for example because its account was deleted. */
    public static final String STATE_FAILED = "failed";

    private static LocalStore instance;
    private final Context context;

    /** An answer the server gave earlier, and when. */
    public static final class Cached {
        public final String body;
        public final long savedAt;

        Cached(String body, long savedAt) {
            this.body = body;
            this.savedAt = savedAt;
        }
    }

    /** A transaction added, changed or deleted on this phone that the server hasn't taken yet. */
    public static final class Change {
        public long id;
        public String serverId;
        /** The user signed in when the change was made; it is only sent, and only shown, while they are signed in. */
        public String userName;
        /** Goes up with each edit the user makes, so a send that started from an older version doesn't remove the newer one. */
        public long revision;
        /** When it was last sent without an answer coming back, in milliseconds. */
        public long lastAttemptAt;
        /** The book it belongs to; null is the user's own book. */
        public String bookId;
        public String kind;
        /** The server's id of the transaction; null for one added on this phone. */
        public String transactionId;
        /** What add.json, modify.json or delete.json is sent. */
        public JSONObject body;
        /** The server's version the change was made to, to notice when someone changed it meanwhile. */
        public JSONObject base;
        /** Names to show it by while the lists of accounts and categories may be out of reach: category, account, destinationAccount, currency. */
        public JSONObject labels = new JSONObject();
        public long createdAt;
        public String state = STATE_WAITING;
        public String message;
        /** The server's version when they conflict; null when it was deleted there. */
        public JSONObject serverCopy;
        public int attempts;

        public boolean isWaiting() {
            return STATE_WAITING.equals(state);
        }

        public String label(String key) {
            return labels.optString(key, "");
        }
    }

    /** What the server got for a change that is no longer waiting, for an add or edit screen still open on it. */
    public static final class Sent {
        public final String transactionId;
        /** The server's version after the change; null after a deletion. */
        public final JSONObject serverCopy;

        Sent(String transactionId, JSONObject serverCopy) {
            this.transactionId = transactionId;
            this.serverCopy = serverCopy;
        }
    }

    private LocalStore(Context context) {
        super(context.getApplicationContext(), NAME, null, VERSION);
        this.context = context.getApplicationContext();
    }

    public static synchronized LocalStore get(Context context) {
        if (instance == null) {
            instance = new LocalStore(context);
        }

        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE cache (server_id TEXT NOT NULL, book_id TEXT NOT NULL, path TEXT NOT NULL, body TEXT NOT NULL, "
            + "saved_at INTEGER NOT NULL, PRIMARY KEY (server_id, book_id, path))");
        db.execSQL("CREATE TABLE outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, server_id TEXT NOT NULL, book_id TEXT NOT NULL, "
            + "kind TEXT NOT NULL, transaction_id TEXT, body TEXT NOT NULL, base TEXT, labels TEXT, created_at INTEGER NOT NULL, "
            + "state TEXT NOT NULL, message TEXT, server_copy TEXT, attempts INTEGER NOT NULL DEFAULT 0, "
            + "user_name TEXT, revision INTEGER NOT NULL DEFAULT 0, last_attempt_at INTEGER NOT NULL DEFAULT 0)");
        createSent(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE outbox ADD COLUMN user_name TEXT");
            db.execSQL("ALTER TABLE outbox ADD COLUMN revision INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE outbox ADD COLUMN last_attempt_at INTEGER NOT NULL DEFAULT 0");
            createSent(db);

            // changes from before users were recorded belong to whoever is signed in to their server now
            for (Server server : ServerStore.get(context).getServers()) {
                if (server.isSignedIn()) {
                    db.execSQL("UPDATE outbox SET user_name = ? WHERE server_id = ? AND user_name IS NULL",
                        new Object[] { Sync.userOf(server), server.id });
                }
            }
        }
    }

    private static void createSent(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sent (change_id INTEGER PRIMARY KEY, server_id TEXT NOT NULL, transaction_id TEXT, "
            + "server_copy TEXT, sent_at INTEGER NOT NULL)");
    }

    // answers

    public void putCached(String serverId, String bookId, String path, String body) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("server_id", serverId);
        values.put("book_id", key(bookId));
        values.put("path", path);
        values.put("body", body);
        values.put("saved_at", System.currentTimeMillis());
        db.insertWithOnConflict("cache", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        db.execSQL("DELETE FROM cache WHERE server_id = ? AND rowid NOT IN (SELECT rowid FROM cache WHERE server_id = ? ORDER BY saved_at DESC LIMIT "
            + MAX_CACHED + ")", new Object[] { serverId, serverId });
    }

    public Cached getCached(String serverId, String bookId, String path) {
        try (Cursor cursor = getReadableDatabase().query("cache", new String[] { "body", "saved_at" },
            "server_id = ? AND book_id = ? AND path = ?", new String[] { serverId, key(bookId), path }, null, null, null)) {
            return cursor.moveToFirst() ? new Cached(cursor.getString(0), cursor.getLong(1)) : null;
        }
    }

    /** Forgets what a server answered, after signing out of it or removing it. */
    public void clearCache(String serverId) {
        getWritableDatabase().delete("cache", "server_id = ?", new String[] { serverId });
    }

    // changes waiting to be sent

    private ContentValues values(Change change) {
        ContentValues values = new ContentValues();
        values.put("server_id", change.serverId);
        values.put("user_name", change.userName);
        values.put("book_id", key(change.bookId));
        values.put("kind", change.kind);
        values.put("transaction_id", change.transactionId);
        values.put("body", change.body.toString());
        values.put("base", change.base == null ? null : change.base.toString());
        values.put("labels", change.labels.toString());
        values.put("created_at", change.createdAt);
        values.put("state", change.state);
        values.put("message", change.message);
        values.put("server_copy", change.serverCopy == null ? null : change.serverCopy.toString());
        values.put("attempts", change.attempts);
        values.put("last_attempt_at", change.lastAttemptAt);
        return values;
    }

    /** Adds a new change. */
    public synchronized void insert(Change change) {
        ContentValues values = values(change);
        values.put("revision", change.revision);
        change.id = getWritableDatabase().insert("outbox", null, values);
    }

    /** Saves the user's edit of a change; false when it is gone meanwhile, because it was sent or settled. */
    public synchronized boolean update(Change change) {
        SQLiteDatabase db = getWritableDatabase();
        String[] id = { String.valueOf(change.id) };
        ContentValues values = values(change);

        try (Cursor cursor = db.query("outbox", new String[] { "revision" }, "id = ?", id, null, null, null)) {
            if (!cursor.moveToFirst()) {
                return false;
            }

            change.revision = cursor.getLong(0) + 1;
        }

        values.put("revision", change.revision);
        return db.update("outbox", values, "id = ?", id) > 0;
    }

    /** Saves what sending found out about a change, unless the user edited it since it was read. */
    public synchronized boolean updateIfUnchanged(Change change) {
        return getWritableDatabase().update("outbox", values(change), "id = ? AND revision = ?",
            new String[] { String.valueOf(change.id), String.valueOf(change.revision) }) > 0;
    }

    public synchronized void remove(long id) {
        getWritableDatabase().delete("outbox", "id = ?", new String[] { String.valueOf(id) });
    }

    /** Removes a change that was sent, unless the user edited it while it was on its way. */
    public synchronized boolean removeIfUnchanged(Change change) {
        return getWritableDatabase().delete("outbox", "id = ? AND revision = ?",
            new String[] { String.valueOf(change.id), String.valueOf(change.revision) }) > 0;
    }

    /** Remembers what the server got for a change, in case an add or edit screen is still open on it. */
    public synchronized void markSent(Change change, String transactionId, JSONObject serverCopy) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("change_id", change.id);
        values.put("server_id", change.serverId);
        values.put("transaction_id", transactionId);
        values.put("server_copy", serverCopy == null ? null : serverCopy.toString());
        values.put("sent_at", System.currentTimeMillis());
        db.insertWithOnConflict("sent", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        db.delete("sent", "sent_at < ?", new String[] { String.valueOf(System.currentTimeMillis() - SENT_KEPT_MILLIS) });
    }

    /** What the server got for a change that is no longer waiting, or null when it wasn't sent (it was discarded). */
    public synchronized Sent sent(long changeId) {
        try (Cursor cursor = getReadableDatabase().query("sent", new String[] { "transaction_id", "server_copy" },
            "change_id = ?", new String[] { String.valueOf(changeId) }, null, null, null)) {
            return cursor.moveToFirst() ? new Sent(cursor.getString(0), json(cursor.getString(1))) : null;
        }
    }

    public synchronized void removeAll(String serverId) {
        getWritableDatabase().delete("outbox", "server_id = ?", new String[] { serverId });
        getWritableDatabase().delete("sent", "server_id = ?", new String[] { serverId });
    }

    /** All users' changes for a server, oldest first. */
    public synchronized List<Change> changes(String serverId) {
        return query("server_id = ?", new String[] { serverId });
    }

    /** One user's changes for a server, oldest first, which is the order they are sent in. */
    public synchronized List<Change> changes(String serverId, String userName) {
        return query("server_id = ? AND user_name = ?", new String[] { serverId, userName });
    }

    public synchronized List<Change> allChanges() {
        return query(null, null);
    }

    public synchronized Change change(long id) {
        List<Change> list = query("id = ?", new String[] { String.valueOf(id) });
        return list.isEmpty() ? null : list.get(0);
    }

    /** The user's change waiting for one of the server's transactions, if any. */
    public synchronized Change changeFor(String serverId, String userName, String transactionId) {
        List<Change> list = query("server_id = ? AND user_name = ? AND transaction_id = ?", new String[] { serverId, userName, transactionId });
        return list.isEmpty() ? null : list.get(0);
    }

    private List<Change> query(String selection, String[] args) {
        List<Change> list = new ArrayList<>();

        try (Cursor cursor = getReadableDatabase().query("outbox", null, selection, args, null, null, "id")) {
            while (cursor.moveToNext()) {
                Change change = new Change();
                change.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
                change.serverId = cursor.getString(cursor.getColumnIndexOrThrow("server_id"));
                change.userName = cursor.getString(cursor.getColumnIndexOrThrow("user_name"));
                change.revision = cursor.getLong(cursor.getColumnIndexOrThrow("revision"));
                change.lastAttemptAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_attempt_at"));
                String bookId = cursor.getString(cursor.getColumnIndexOrThrow("book_id"));
                change.bookId = bookId.isEmpty() ? null : bookId;
                change.kind = cursor.getString(cursor.getColumnIndexOrThrow("kind"));
                change.transactionId = cursor.getString(cursor.getColumnIndexOrThrow("transaction_id"));
                change.body = json(cursor.getString(cursor.getColumnIndexOrThrow("body")));
                change.base = json(cursor.getString(cursor.getColumnIndexOrThrow("base")));
                JSONObject labels = json(cursor.getString(cursor.getColumnIndexOrThrow("labels")));
                change.labels = labels == null ? new JSONObject() : labels;
                change.createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"));
                change.state = cursor.getString(cursor.getColumnIndexOrThrow("state"));
                change.message = cursor.getString(cursor.getColumnIndexOrThrow("message"));
                change.serverCopy = json(cursor.getString(cursor.getColumnIndexOrThrow("server_copy")));
                change.attempts = cursor.getInt(cursor.getColumnIndexOrThrow("attempts"));

                if (change.body != null) {
                    list.add(change);
                }
            }
        }

        return list;
    }

    private static JSONObject json(String text) {
        if (text == null) {
            return null;
        }

        try {
            return new JSONObject(text);
        } catch (JSONException ex) {
            return null;
        }
    }

    private static String key(String bookId) {
        return bookId == null ? "" : bookId;
    }
}
