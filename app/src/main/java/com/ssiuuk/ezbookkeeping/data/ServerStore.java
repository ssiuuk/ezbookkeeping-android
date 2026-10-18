package com.ssiuuk.ezbookkeeping.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Keeps the list of servers the user added and which one is in use. */
public final class ServerStore {
    private static final String PREFERENCES_NAME = "settings";
    private static final String KEY_SERVERS = "servers";
    private static final String KEY_ACTIVE_SERVER_ID = "active_server_id";
    private static final String KEY_LEGACY_SERVER_URL = "server_url";
    private static final String KEY_THEME = "theme";

    private static ServerStore instance;

    private final SharedPreferences preferences;
    private final List<Server> servers = new ArrayList<>();
    private String activeServerId;

    private ServerStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        load();
    }

    public static synchronized ServerStore get(Context context) {
        if (instance == null) {
            instance = new ServerStore(context.getApplicationContext());
        }

        return instance;
    }

    public synchronized List<Server> getServers() {
        return new ArrayList<>(servers);
    }

    public synchronized Server getActiveServer() {
        for (Server server : servers) {
            if (server.id.equals(activeServerId)) {
                return server;
            }
        }

        return null;
    }

    public synchronized Server findById(String id) {
        for (Server server : servers) {
            if (server.id.equals(id)) {
                return server;
            }
        }

        return null;
    }

    public synchronized Server findByUrl(String url) {
        for (Server server : servers) {
            if (server.url.equals(url)) {
                return server;
            }
        }

        return null;
    }

    public synchronized Server add(String name, String url) {
        Server existing = findByUrl(url);

        if (existing != null) {
            existing.name = name;
            save();
            return existing;
        }

        Server server = new Server(UUID.randomUUID().toString(), name, url);
        servers.add(server);
        save();
        return server;
    }

    public synchronized void setActive(Server server) {
        activeServerId = server == null ? null : server.id;
        save();
    }

    public synchronized void remove(Server server) {
        servers.remove(server);

        if (server.id.equals(activeServerId)) {
            activeServerId = servers.isEmpty() ? null : servers.get(0).id;
        }

        save();
    }

    public synchronized void update(Server server) {
        save();
    }

    public synchronized void signOut(Server server) {
        server.token = null;
        server.userJson = null;
        server.bookId = null;
        server.bookName = null;
        save();
    }

    public int getThemeMode() {
        return preferences.getInt(KEY_THEME, -1);
    }

    public void setThemeMode(int mode) {
        preferences.edit().putInt(KEY_THEME, mode).apply();
    }

    private void load() {
        String json = preferences.getString(KEY_SERVERS, null);

        if (json != null) {
            try {
                JSONArray array = new JSONArray(json);

                for (int i = 0; i < array.length(); i++) {
                    servers.add(Server.fromJson(array.getJSONObject(i)));
                }
            } catch (JSONException ex) {
                servers.clear();
            }
        }

        activeServerId = preferences.getString(KEY_ACTIVE_SERVER_ID, null);

        // the first version of the app kept a single server address
        String legacyUrl = preferences.getString(KEY_LEGACY_SERVER_URL, null);

        if (legacyUrl != null) {
            Server server = findByUrl(legacyUrl);

            if (server == null) {
                server = new Server(UUID.randomUUID().toString(), "", legacyUrl);
                servers.add(server);
            }

            if (activeServerId == null) {
                activeServerId = server.id;
            }

            preferences.edit().remove(KEY_LEGACY_SERVER_URL).apply();
            save();
        }
    }

    private void save() {
        JSONArray array = new JSONArray();

        try {
            for (Server server : servers) {
                array.put(server.toJson());
            }
        } catch (JSONException ex) {
            return;
        }

        preferences.edit()
            .putString(KEY_SERVERS, array.toString())
            .putString(KEY_ACTIVE_SERVER_ID, activeServerId)
            .apply();
    }

    /** Turns what the user typed into a server base address, or null when it isn't one. */
    public static String normalizeServerUrl(String input) {
        if (input == null) {
            return null;
        }

        String url = input.trim();

        if (url.isEmpty()) {
            return null;
        }

        if (!url.contains("://")) {
            url = "https://" + url;
        }

        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);

        if ((!scheme.equals("http") && !scheme.equals("https")) || TextUtils.isEmpty(uri.getHost())) {
            return null;
        }

        String path = uri.getPath() == null ? "" : uri.getPath();

        // people often paste the address of a page, so drop a trailing page name like /mobile or /desktop
        if (path.endsWith("/mobile") || path.endsWith("/desktop") || path.endsWith("/index.html")) {
            path = path.substring(0, path.lastIndexOf('/'));
        }

        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        return scheme + "://" + uri.getEncodedAuthority() + path;
    }
}
