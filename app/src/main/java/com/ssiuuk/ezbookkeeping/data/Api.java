package com.ssiuuk.ezbookkeeping.data;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Calls the ezBookkeeping JSON API of one server on a background thread. */
public final class Api {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int TIMEOUT_MILLIS = 20000;

    public interface Parser<T> {
        T parse(Object result) throws JSONException;
    }

    public interface Callback<T> {
        void onSuccess(T result);

        void onError(ApiException error);
    }

    /** Lets the app react once when the server no longer accepts the saved sign-in. */
    public interface UnauthorizedListener {
        void onUnauthorized(Server server);
    }

    private static UnauthorizedListener unauthorizedListener;

    private final Server server;
    private String tokenOverride;
    private boolean useBook = true;
    private boolean bookOverridden;
    private String bookOverride;

    public Api(Server server) {
        this.server = server;
    }

    public static void setUnauthorizedListener(UnauthorizedListener listener) {
        unauthorizedListener = listener;
    }

    /** Uses this token instead of the saved one, for the second step of a two-factor sign-in. */
    public Api withToken(String token) {
        tokenOverride = token;
        return this;
    }

    /** Sends the request to the user's own book, whichever book is selected. */
    public Api withoutBook() {
        useBook = false;
        return this;
    }

    /** Sends the request to this book instead of the selected one; null is the user's own book. */
    public Api withBook(String bookId) {
        bookOverridden = true;
        bookOverride = bookId;
        return this;
    }

    public Server getServer() {
        return server;
    }

    public static Map<String, String> query(String... keysAndValues) {
        Map<String, String> map = new LinkedHashMap<>();

        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            if (keysAndValues[i + 1] != null) {
                map.put(keysAndValues[i], keysAndValues[i + 1]);
            }
        }

        return map;
    }

    public <T> void get(String path, Map<String, String> query, Parser<T> parser, Callback<T> callback) {
        String fullPath = path;

        if (query != null && !query.isEmpty()) {
            StringBuilder builder = new StringBuilder(path).append('?');
            boolean first = true;

            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (!first) {
                    builder.append('&');
                }

                builder.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
                first = false;
            }

            fullPath = builder.toString();
        }

        enqueue("GET", fullPath, null, parser, callback);
    }

    public <T> void post(String path, JSONObject body, Parser<T> parser, Callback<T> callback) {
        enqueue("POST", path, body == null ? new JSONObject() : body, parser, callback);
    }

    /** Uploads one file as a multipart form, for transaction pictures. */
    public <T> void upload(String path, String field, String fileName, String contentType, byte[] data, Map<String, String> fields,
                           Parser<T> parser, Callback<T> callback) {
        EXECUTOR.execute(() -> {
            try {
                Object result = execute("POST", path, new Multipart(field, fileName, contentType, data, fields));
                T parsed = parser == null ? null : parser.parse(result);
                MAIN.post(() -> callback.onSuccess(parsed));
            } catch (ApiException ex) {
                MAIN.post(() -> callback.onError(ex));
            } catch (JSONException ex) {
                ApiException error = new ApiException(0, 0, "Unexpected response from the server");
                MAIN.post(() -> callback.onError(error));
            }
        });
    }

    /** Downloads a file the server protects with the sign-in, such as a transaction picture. */
    public void download(String url, Callback<byte[]> callback) {
        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;

            try {
                StringBuilder full = new StringBuilder(url).append(url.contains("?") ? '&' : '?').append("token=").append(encode(server.token == null ? "" : server.token));
                String bookId = currentBookId();

                if (bookId != null) {
                    full.append("&book_id=").append(encode(bookId));
                }

                connection = (HttpURLConnection) new URL(full.toString()).openConnection();
                connection.setConnectTimeout(TIMEOUT_MILLIS);
                connection.setReadTimeout(TIMEOUT_MILLIS);
                int status = connection.getResponseCode();

                if (status != 200) {
                    ApiException error = new ApiException(status, 0, "The server answered with error " + status);
                    MAIN.post(() -> callback.onError(error));
                    return;
                }

                byte[] bytes = readBytes(connection.getInputStream());
                MAIN.post(() -> callback.onSuccess(bytes));
            } catch (IOException ex) {
                ApiException error = new ApiException(0, 0, "Can't reach the server");
                MAIN.post(() -> callback.onError(error));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private String currentBookId() {
        if (!useBook || tokenOverride != null) {
            return null;
        }

        return bookOverridden ? bookOverride : server.bookId;
    }

    private static final class Multipart {
        final String field;
        final String fileName;
        final String contentType;
        final byte[] data;
        final Map<String, String> fields;

        Multipart(String field, String fileName, String contentType, byte[] data, Map<String, String> fields) {
            this.field = field;
            this.fileName = fileName;
            this.contentType = contentType;
            this.data = data;
            this.fields = fields;
        }

        byte[] encode(String boundary) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            if (fields != null) {
                for (Map.Entry<String, String> entry : fields.entrySet()) {
                    output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + entry.getKey() + "\"\r\n\r\n"
                        + entry.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
                }
            }

            output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field + "\"; filename=\"" + fileName
                + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(data);
            output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return output.toByteArray();
        }
    }

    private <T> void enqueue(String method, String path, JSONObject body, Parser<T> parser, Callback<T> callback) {
        EXECUTOR.execute(() -> {
            try {
                Object result = execute(method, path, body);
                T parsed = parser == null ? null : parser.parse(result);
                MAIN.post(() -> callback.onSuccess(parsed));
            } catch (ApiException ex) {
                if (ex.isUnauthorized() && tokenOverride == null && unauthorizedListener != null) {
                    MAIN.post(() -> unauthorizedListener.onUnauthorized(server));
                }

                MAIN.post(() -> callback.onError(ex));
            } catch (JSONException ex) {
                ApiException error = new ApiException(0, 0, "Unexpected response from the server");
                MAIN.post(() -> callback.onError(error));
            }
        });
    }

    private Object execute(String method, String path, Object body) throws ApiException, JSONException {
        HttpURLConnection connection = null;

        try {
            URL url = new URL(server.url + "/api/" + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag());

            TimeZone timeZone = TimeZone.getDefault();
            connection.setRequestProperty("X-Timezone-Offset", String.valueOf(timeZone.getOffset(System.currentTimeMillis()) / 60000));
            connection.setRequestProperty("X-Timezone-Name", timeZone.getID());

            String token = tokenOverride != null ? tokenOverride : server.token;

            if (token != null && !token.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }

            String bookId = currentBookId();

            if (bookId != null) {
                connection.setRequestProperty("X-Book-Id", bookId);
            }

            if (body != null) {
                byte[] bytes;

                if (body instanceof Multipart) {
                    String boundary = "ezb" + System.nanoTime();
                    bytes = ((Multipart) body).encode(boundary);
                    connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                } else {
                    bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                }

                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);

                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = stream == null ? "" : readAll(stream);
            JSONObject json;

            try {
                json = new JSONObject(text);
            } catch (JSONException ex) {
                if (status == 404) {
                    throw new ApiException(status, 0, "This server doesn't support this feature");
                }

                throw new ApiException(status, 0, status >= 400
                    ? "The server answered with error " + status
                    : "This address doesn't look like an ezBookkeeping server");
            }

            if (!json.optBoolean("success", false)) {
                String message = json.optString("errorMessage", "");
                throw new ApiException(status, json.optInt("errorCode", 0), message.isEmpty() ? "The server answered with error " + status : message);
            }

            return json.opt("result");
        } catch (IOException ex) {
            String message = ex.getMessage();
            throw new ApiException(0, 0, "Can't reach the server" + (message == null ? "" : " (" + message + ")"));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** What the server's sign-in page offers, read from its public server_settings.js. */
    public static final class ServerInfo {
        public boolean internalAuth = true;
        public boolean oauth2;
        public boolean register;
        public boolean pictures = true;
    }

    /** Checks that an address is an ezBookkeeping server before it is saved. */
    public static void checkServer(String baseUrl, Callback<ServerInfo> callback) {
        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;

            try {
                connection = (HttpURLConnection) new URL(baseUrl + "/server_settings.js").openConnection();
                connection.setConnectTimeout(TIMEOUT_MILLIS);
                connection.setReadTimeout(TIMEOUT_MILLIS);
                int status = connection.getResponseCode();
                String text = status == 200 ? readAll(connection.getInputStream()) : "";

                if (!text.contains("EZBOOKKEEPING_SERVER_SETTINGS")) {
                    ApiException error = new ApiException(status, 0, null);
                    MAIN.post(() -> callback.onError(error));
                    return;
                }

                ServerInfo info = new ServerInfo();
                info.internalAuth = readFlag(text, "a", true);
                info.oauth2 = readFlag(text, "o", false);
                info.register = readFlag(text, "r", false);
                info.pictures = readFlag(text, "p", true);
                MAIN.post(() -> callback.onSuccess(info));
            } catch (IOException ex) {
                String message = ex.getMessage();
                ApiException error = new ApiException(0, 0, "Can't reach the server" + (message == null ? "" : " (" + message + ")"));
                MAIN.post(() -> callback.onError(error));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private static boolean readFlag(String script, String key, boolean fallback) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[\\s*['\"]" + key + "['\"]\\s*\\]\\s*=\\s*([01])").matcher(script);
        return matcher.find() ? "1".equals(matcher.group(1)) : fallback;
    }

    private static byte[] readBytes(InputStream stream) throws IOException {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;

            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }

            return output.toByteArray();
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;

            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }

            return output.toString("UTF-8");
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (IOException ex) {
            return value;
        }
    }
}
