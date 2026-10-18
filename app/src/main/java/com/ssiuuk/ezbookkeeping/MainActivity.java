package com.ssiuuk.ezbookkeeping;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {
    public static final String ACTION_CHANGE_SERVER = "com.ssiuuk.ezbookkeeping.CHANGE_SERVER";

    private static final String PREFERENCES_NAME = "settings";
    private static final String PREFERENCE_SERVER_URL = "server_url";
    private static final String USER_AGENT_SUFFIX = " ezBookkeepingAndroid";

    private static final int REQUEST_FILE_CHOOSER = 1;
    private static final int REQUEST_LOCATION_PERMISSION = 2;

    private WebView webView;
    private ProgressBar progressBar;
    private View setupView;
    private View errorView;
    private TextView errorDetailView;
    private EditText serverAddressInput;

    private String serverUrl;
    private Uri serverUri;

    private ValueCallback<Uri[]> pendingFileChooserCallback;
    private String pendingGeolocationOrigin;
    private GeolocationPermissions.Callback pendingGeolocationCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // draw edge to edge on every version, the same as targetSdk 35 does on Android 15
            getWindow().setDecorFitsSystemWindows(false);
        }

        webView = findViewById(R.id.web_view);
        progressBar = findViewById(R.id.progress);
        setupView = findViewById(R.id.setup_view);
        errorView = findViewById(R.id.error_view);
        errorDetailView = findViewById(R.id.error_detail);
        serverAddressInput = findViewById(R.id.server_address);

        applySystemBarInsets(findViewById(android.R.id.content));
        setupWebView();
        setupButtons();

        String savedServerUrl = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).getString(PREFERENCE_SERVER_URL, null);

        if (savedServerUrl != null) {
            setServerUrl(savedServerUrl);
        }

        if (savedServerUrl == null || ACTION_CHANGE_SERVER.equals(getIntent().getAction())) {
            showSetup(savedServerUrl);
        } else {
            if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
                loadServer();
            } else {
                showWebView();
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        if (ACTION_CHANGE_SERVER.equals(intent.getAction())) {
            showSetup(serverUrl);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
            return;
        }

        if (setupView.getVisibility() == View.VISIBLE && serverUrl != null) {
            // leaving the setup screen without changes goes back to the current server
            loadServer();
            return;
        }

        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_FILE_CHOOSER) {
            if (pendingFileChooserCallback != null) {
                pendingFileChooserCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                pendingFileChooserCallback = null;
            }

            return;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;

            if (pendingGeolocationCallback != null) {
                pendingGeolocationCallback.invoke(pendingGeolocationOrigin, granted, false);
            }

            pendingGeolocationCallback = null;
            pendingGeolocationOrigin = null;
            return;
        }

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void applySystemBarInsets(View root) {
        // targetSdk 35 draws the app edge to edge, so keep the content clear of the status bar, navigation bar and keyboard
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            }

            return insets;
        });
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + USER_AGENT_SUFFIX);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();

                if (isServerUri(uri)) {
                    return false;
                }

                openExternally(uri);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError(String.valueOf(error.getDescription()));
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // never accept an invalid certificate, the app holds the user's login token
                handler.cancel();
                showError(getString(R.string.load_failed_title) + " (SSL)");
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (pendingFileChooserCallback != null) {
                    pendingFileChooserCallback.onReceiveValue(null);
                }

                pendingFileChooserCallback = filePathCallback;

                try {
                    startActivityForResult(fileChooserParams.createIntent(), REQUEST_FILE_CHOOSER);
                } catch (ActivityNotFoundException ex) {
                    pendingFileChooserCallback = null;
                    filePathCallback.onReceiveValue(null);
                    return false;
                }

                return true;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                    return;
                }

                pendingGeolocationOrigin = origin;
                pendingGeolocationCallback = callback;
                requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION }, REQUEST_LOCATION_PERMISSION);
            }
        });
    }

    private void setupButtons() {
        Button connectButton = findViewById(R.id.connect_button);
        connectButton.setOnClickListener(view -> connect());

        serverAddressInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                connect();
                return true;
            }

            return false;
        });

        findViewById(R.id.retry_button).setOnClickListener(view -> loadServer());
        findViewById(R.id.change_server_button).setOnClickListener(view -> showSetup(serverUrl));
    }

    private void connect() {
        String url = normalizeServerUrl(serverAddressInput.getText().toString());

        if (url == null) {
            Toast.makeText(this, R.string.invalid_server_address, Toast.LENGTH_SHORT).show();
            return;
        }

        if (!url.equals(serverUrl)) {
            webView.clearHistory();
        }

        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit().putString(PREFERENCE_SERVER_URL, url).apply();
        setServerUrl(url);
        loadServer();
    }

    private void setServerUrl(String url) {
        serverUrl = url;
        serverUri = Uri.parse(url);
    }

    private void loadServer() {
        showWebView();
        // the server's index page redirects to the mobile version for phones
        webView.loadUrl(serverUrl + "/");
    }

    private void showWebView() {
        setupView.setVisibility(View.GONE);
        errorView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
    }

    private void showSetup(String currentServerUrl) {
        webView.setVisibility(View.GONE);
        errorView.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        setupView.setVisibility(View.VISIBLE);

        if (currentServerUrl != null) {
            serverAddressInput.setText(currentServerUrl);
            serverAddressInput.setSelection(currentServerUrl.length());
        }

        serverAddressInput.requestFocus();
    }

    private void showError(String detail) {
        webView.setVisibility(View.GONE);
        setupView.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        errorDetailView.setText(serverUrl + "\n" + (detail == null ? "" : detail));
        errorView.setVisibility(View.VISIBLE);
    }

    private boolean isServerUri(Uri uri) {
        if (serverUri == null || uri == null) {
            return false;
        }

        String scheme = uri.getScheme();

        if ("about".equals(scheme) || "blob".equals(scheme) || "data".equals(scheme)) {
            return true;
        }

        return TextUtils.equals(scheme, serverUri.getScheme())
            && TextUtils.equals(uri.getHost(), serverUri.getHost())
            && uri.getPort() == serverUri.getPort();
    }

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ex) {
            // nothing on the phone can open this link
        }
    }

    static String normalizeServerUrl(String input) {
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

        // the web app is opened at its index page, so drop a trailing page name like /mobile or /desktop
        if (path.endsWith("/mobile") || path.endsWith("/desktop") || path.endsWith("/index.html")) {
            path = path.substring(0, path.lastIndexOf('/'));
        }

        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        String authority = uri.getEncodedAuthority();
        return scheme + "://" + authority + path;
    }
}
