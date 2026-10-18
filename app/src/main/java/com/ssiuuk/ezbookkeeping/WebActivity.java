package com.ssiuuk.ezbookkeeping;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
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
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.ssiuuk.ezbookkeeping.data.Api;
import com.ssiuuk.ezbookkeeping.data.ApiException;
import com.ssiuuk.ezbookkeeping.data.Server;
import com.ssiuuk.ezbookkeeping.data.ServerStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * Shows one of the server's mobile web pages (reports, budgets and other less used pages) inside the app,
 * already signed in. In sign-in mode it shows the web sign-in page and hands the resulting sign-in back.
 */
public class WebActivity extends BaseActivity {
    public static final String EXTRA_ROUTE = "route";
    public static final String EXTRA_SIGN_IN = "sign_in";
    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_USER = "user";

    private static final String USER_AGENT_SUFFIX = " ezBookkeepingAndroid";

    private Server server;
    private Uri serverUri;
    private boolean signInMode;
    private String targetUrl;
    private boolean targetReached;

    private WebView webView;
    private View progress;
    private View errorView;

    private ValueCallback<Uri[]> pendingFileCallback;
    private String pendingGeolocationOrigin;
    private GeolocationPermissions.Callback pendingGeolocationCallback;

    private final ActivityResultLauncher<Intent> fileChooser = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (pendingFileCallback != null) {
            pendingFileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.getResultCode(), result.getData()));
            pendingFileCallback = null;
        }
    });

    private final ActivityResultLauncher<String[]> locationPermission = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), granted -> {
        boolean allowed = false;

        for (Map.Entry<String, Boolean> entry : granted.entrySet()) {
            allowed |= Boolean.TRUE.equals(entry.getValue());
        }

        if (pendingGeolocationCallback != null) {
            pendingGeolocationCallback.invoke(pendingGeolocationOrigin, allowed, false);
        }

        pendingGeolocationCallback = null;
        pendingGeolocationOrigin = null;
    });

    public static Intent intent(Context context, String route) {
        return new Intent(context, WebActivity.class).putExtra(EXTRA_ROUTE, route);
    }

    public static Intent signInIntent(Context context) {
        return new Intent(context, WebActivity.class).putExtra(EXTRA_SIGN_IN, true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        server = ServerStore.get(this).getActiveServer();

        if (server == null) {
            finish();
            return;
        }

        setContentView(R.layout.activity_web);
        padForSystemBars(findViewById(R.id.root), true, true);

        serverUri = Uri.parse(server.url);
        signInMode = getIntent().getBooleanExtra(EXTRA_SIGN_IN, false);
        String route = getIntent().getStringExtra(EXTRA_ROUTE);
        targetUrl = WebSession.mobileUrl(server, signInMode ? "/login" : (route == null ? "/" : route));

        webView = findViewById(R.id.web_view);
        progress = findViewById(R.id.progress);
        errorView = findViewById(R.id.error_view);
        findViewById(R.id.retry_button).setOnClickListener(view -> load());

        setupWebView();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack() && errorView.getVisibility() != View.VISIBLE) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            load();
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
        if (webView != null) {
            webView.destroy();
        }

        super.onDestroy();
    }

    private void load() {
        errorView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        targetReached = false;
        String loader = signInMode ? WebSession.signedOutLoader(targetUrl) : WebSession.signedInLoader(server, targetUrl);
        webView.loadDataWithBaseURL(server.url + "/mobile", loader, "text/html", "UTF-8", null);
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + USER_AGENT_SUFFIX);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, signInMode);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();

                // single sign-on goes through the identity provider's pages, so sign-in mode stays in the app
                if (signInMode || isServerUri(uri)) {
                    return false;
                }

                openExternally(uri);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                onRouteChanged(url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError(String.valueOf(error.getDescription()));
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // never accept an invalid certificate, the page holds the user's sign-in
                handler.cancel();
                showError("SSL");
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (pendingFileCallback != null) {
                    pendingFileCallback.onReceiveValue(null);
                }

                pendingFileCallback = filePathCallback;

                try {
                    fileChooser.launch(fileChooserParams.createIntent());
                } catch (ActivityNotFoundException ex) {
                    pendingFileCallback = null;
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
                locationPermission.launch(new String[] { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION });
            }
        });
    }

    private void onRouteChanged(String url) {
        if (url == null || !url.startsWith(server.url)) {
            return;
        }

        String route = routeOf(url);

        if (signInMode) {
            if (!"/login".equals(route) && !route.startsWith("/signup")) {
                readWebSignIn();
            }

            return;
        }

        if (!targetReached) {
            targetReached = route.equals(routeOf(targetUrl));
            return;
        }

        if ("/".equals(route)) {
            // the home page is the native overview, so going "back home" on a web page closes it
            finish();
        } else if ("/login".equals(route)) {
            // the web page didn't accept the sign-in; check it natively, which signs out if it really expired
            new Api(server).get("v1/users/profile/get.json", null, null, new Api.Callback<Object>() {
                @Override
                public void onSuccess(Object result) {
                }

                @Override
                public void onError(ApiException error) {
                }
            });
            finish();
        }
    }

    private static String routeOf(String url) {
        int hash = url.indexOf("#!");

        if (hash < 0) {
            return "/";
        }

        String route = url.substring(hash + 2);
        int query = route.indexOf('?');

        if (query >= 0) {
            route = route.substring(0, query);
        }

        return route.isEmpty() ? "/" : route;
    }

    private void readWebSignIn() {
        String script = "(function(){try{return JSON.stringify({t:localStorage.getItem('" + WebSession.TOKEN_KEY + "'),u:localStorage.getItem('" + WebSession.USER_KEY + "')});}catch(e){return null;}})()";
        webView.evaluateJavascript(script, value -> {
            try {
                if (value == null || "null".equals(value)) {
                    return;
                }

                JSONObject json = new JSONObject(new JSONArray("[" + value + "]").getString(0));
                String token = json.isNull("t") ? null : json.optString("t");

                // a token protected by the web app's PIN lock isn't a usable sign-in
                if (TextUtils.isEmpty(token) || !token.startsWith("ey")) {
                    return;
                }

                Intent data = new Intent();
                data.putExtra(EXTRA_TOKEN, token);
                data.putExtra(EXTRA_USER, json.isNull("u") ? null : json.optString("u"));
                setResult(RESULT_OK, data);
                finish();
            } catch (Exception ex) {
                // not signed in yet
            }
        });
    }

    private void showError(String detail) {
        progress.setVisibility(View.GONE);
        webView.setVisibility(View.INVISIBLE);
        ((TextView) findViewById(R.id.error_detail)).setText(server.url + "\n" + detail);
        errorView.setVisibility(View.VISIBLE);
    }

    private boolean isServerUri(Uri uri) {
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
}
