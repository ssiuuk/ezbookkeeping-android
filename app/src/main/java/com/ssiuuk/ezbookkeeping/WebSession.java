package com.ssiuuk.ezbookkeeping;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebStorage;

import com.ssiuuk.ezbookkeeping.data.Server;

import org.json.JSONObject;

/** Shares the native sign-in with the server's web pages that open inside the app. */
final class WebSession {
    static final String TOKEN_KEY = "ebk_user_token";
    static final String USER_KEY = "ebk_user_info";
    static final String BOOK_KEY = "ebk_user_current_book_id";

    private WebSession() {
    }

    static String mobileUrl(Server server, String route) {
        return server.url + "/mobile#!" + route;
    }

    /**
     * A tiny page loaded on the server's own origin: it writes the sign-in where the web app looks for it,
     * then replaces itself with the requested page, so it leaves nothing in the back history.
     */
    static String signedInLoader(Server server, String targetUrl) {
        StringBuilder script = new StringBuilder("try{");

        if (server.token != null) {
            script.append("localStorage.setItem('").append(TOKEN_KEY).append("',").append(JSONObject.quote(server.token)).append(");");
        }

        if (server.userJson != null) {
            script.append("localStorage.setItem('").append(USER_KEY).append("',").append(JSONObject.quote(server.userJson)).append(");");
        }

        if (server.bookId != null) {
            script.append("localStorage.setItem('").append(BOOK_KEY).append("',").append(JSONObject.quote(server.bookId)).append(");");
        } else {
            script.append("localStorage.removeItem('").append(BOOK_KEY).append("');");
        }

        script.append("}catch(e){}location.replace(").append(JSONObject.quote(targetUrl)).append(");");
        return "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width\"></head><body><script>" + script + "</script></body></html>";
    }

    static String signedOutLoader(String targetUrl) {
        return "<!DOCTYPE html><html><body><script>try{localStorage.removeItem('" + TOKEN_KEY + "');localStorage.removeItem('" + USER_KEY + "');localStorage.removeItem('" + BOOK_KEY + "');}catch(e){}location.replace("
            + JSONObject.quote(targetUrl) + ");</script></body></html>";
    }

    static String origin(Server server) {
        Uri uri = Uri.parse(server.url);
        return uri.getScheme() + "://" + uri.getEncodedAuthority();
    }

    /** Forgets what the server's web pages stored in the app, for sign-out and removed servers. */
    static void clear(Context context, Server server) {
        WebStorage.getInstance().deleteOrigin(origin(server));
    }
}
