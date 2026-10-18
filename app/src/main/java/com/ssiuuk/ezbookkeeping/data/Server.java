package com.ssiuuk.ezbookkeeping.data;

import org.json.JSONException;
import org.json.JSONObject;

/** A saved ezBookkeeping server and the sign-in kept for it. */
public final class Server {
    public final String id;
    public String name;
    public String url;
    public String token;
    public String userJson;
    public String bookId;
    public String bookName;

    public Server(String id, String name, String url) {
        this.id = id;
        this.name = name;
        this.url = url;
    }

    public boolean isSignedIn() {
        return token != null && !token.isEmpty();
    }

    public String getDisplayName() {
        return name != null && !name.isEmpty() ? name : getHostLabel();
    }

    public String getHostLabel() {
        int start = url.indexOf("://");
        return start >= 0 ? url.substring(start + 3) : url;
    }

    public JSONObject getUser() {
        if (userJson == null) {
            return new JSONObject();
        }

        try {
            return new JSONObject(userJson);
        } catch (JSONException ex) {
            return new JSONObject();
        }
    }

    public String getDefaultCurrency() {
        String currency = getUser().optString("defaultCurrency", "");
        return currency.isEmpty() ? "USD" : currency;
    }

    public String getUserDisplayName() {
        JSONObject user = getUser();
        String nickname = user.optString("nickname", "");
        return nickname.isEmpty() ? user.optString("username", "") : nickname;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("url", url);
        json.putOpt("token", token);
        json.putOpt("user", userJson);
        json.putOpt("bookId", bookId);
        json.putOpt("bookName", bookName);
        return json;
    }

    static Server fromJson(JSONObject json) {
        Server server = new Server(json.optString("id"), json.optString("name"), json.optString("url"));
        server.token = json.isNull("token") ? null : json.optString("token", null);
        server.userJson = json.isNull("user") ? null : json.optString("user", null);
        server.bookId = json.isNull("bookId") ? null : json.optString("bookId", null);
        server.bookName = json.isNull("bookName") ? null : json.optString("bookName", null);
        return server;
    }
}
