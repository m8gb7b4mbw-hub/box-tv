package fr.boxtv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Box TV : écran d'accueil qui affiche la page servie par le NAS
 * et sait lancer les applis installées sur la box.
 */
public class MainActivity extends Activity {

    static final String DEFAULT_URL = "http://100.99.4.38:8080/home.html";
    static final String PREFS = "boxtv";

    WebView web;
    View fullscreenView;
    WebChromeClient.CustomViewCallback fullscreenCallback;
    FrameLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " BoxTV/1");

        web.addJavascriptInterface(new Bridge(), "BoxTV");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("http://") || url.startsWith("https://")) return false;
                openExternal(url);
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (Build.VERSION.SDK_INT >= 21 && req.isForMainFrame()) showOffline();
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int code, String desc, String failingUrl) {
                showOffline();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                fullscreenView = view;
                fullscreenCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
            }

            @Override
            public void onHideCustomView() {
                if (fullscreenView != null) root.removeView(fullscreenView);
                fullscreenView = null;
                if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden();
                fullscreenCallback = null;
            }
        });

        web.requestFocus();
        web.loadUrl(homeUrl());
    }

    String homeUrl() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        return p.getString("url", DEFAULT_URL);
    }

    // appui sur la touche Accueil alors que Box TV est déjà ouverte : retour à l'accueil
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) {
            String cur = web.getUrl();
            if (cur == null || !cur.startsWith(homeUrl())) web.loadUrl(homeUrl());
        }
    }

    // Retour : la page décide d'abord (fermer une fiche, un lecteur...), sinon page précédente
    @Override
    public void onBackPressed() {
        if (fullscreenView != null) {
            if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden();
            return;
        }
        web.evaluateJavascript("(window.boxBack&&window.boxBack())?'1':'0'", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                if (v != null && v.contains("1")) return;
                if (web.canGoBack()) web.goBack();
            }
        });
    }

    // Menu (ou appui long sur OK sur certaines télécommandes) : réglages de Box TV
    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            askUrl();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            event.startTracking();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            askUrl();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.isTracking() && !event.isCanceled()) {
            onBackPressed();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    void askUrl() {
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        in.setText(homeUrl());
        new AlertDialog.Builder(this)
                .setTitle("Adresse de la page d'accueil (NAS)")
                .setView(in)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    String u = in.getText().toString().trim();
                    if (!u.isEmpty()) {
                        if (!u.startsWith("http")) u = "http://" + u;
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("url", u).apply();
                        web.loadUrl(u);
                    }
                })
                .setNeutralButton("Réglages Android", (d, w) -> openSettings())
                .setNegativeButton("Annuler", null)
                .show();
    }

    void showOffline() {
        String html = "<!doctype html><html><head><meta name=viewport content='width=device-width'>"
                + "<style>body{margin:0;background:#0b0b0d;color:#f5f5f7;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;text-align:center}"
                + "button{font-size:20px;margin:8px;padding:14px 26px;border:0;border-radius:14px;background:#2c2c2e;color:#fff}"
                + "button:focus{background:#fff;color:#000;outline:none}p{color:#a1a1a6}</style></head><body><div>"
                + "<h1>NAS injoignable</h1><p>Vérifie que le NAS est allumé et que Tailscale est connecté sur la box.</p>"
                + "<button autofocus onclick=\"location.href='" + homeUrl().replace("'", "") + "'\">Réessayer</button>"
                + "<button onclick=\"BoxTV.launch('com.tailscale.ipn')\">Ouvrir Tailscale</button>"
                + "<button onclick=\"BoxTV.settings()\">Réglages</button>"
                + "<button onclick=\"BoxTV.editUrl()\">Changer l'adresse</button>"
                + "</div><script>document.querySelector('button').focus();"
                + "document.addEventListener('keydown',function(e){var b=[].slice.call(document.querySelectorAll('button')),i=b.indexOf(document.activeElement);"
                + "if(e.key=='ArrowRight'||e.key=='ArrowDown'){b[(i+1)%b.length].focus();e.preventDefault()}"
                + "if(e.key=='ArrowLeft'||e.key=='ArrowUp'){b[(i-1+b.length)%b.length].focus();e.preventDefault()}});</script></body></html>";
        web.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null);
    }

    void openSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast("Réglages introuvables");
        }
    }

    void openExternal(String url) {
        try {
            Intent i;
            if (url.startsWith("intent:")) i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            else i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("Impossible d'ouvrir ce lien");
        }
    }

    void toast(final String m) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_SHORT).show());
    }

    Intent launchIntent(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = null;
        if (Build.VERSION.SDK_INT >= 21) i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }

    /** Fonctions appelables depuis la page du NAS : BoxTV.launch('com.termux') etc. */
    class Bridge {
        @JavascriptInterface
        public boolean launch(String pkg) {
            Intent i = launchIntent(pkg);
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
                return true;
            } catch (ActivityNotFoundException e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean installed(String pkg) {
            return launchIntent(pkg) != null;
        }

        @JavascriptInterface
        public void openUrl(String url) {
            openExternal(url);
        }

        @JavascriptInterface
        public boolean openUrlIn(String pkg, String url) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.setPackage(pkg);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void settings() {
            openSettings();
        }

        @JavascriptInterface
        public void editUrl() {
            runOnUiThread(() -> askUrl());
        }

        /** true si le remplacement de l'accueil (accessibilité) est activé */
        @JavascriptInterface
        public boolean watcher() {
            try {
                String on = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                return on != null && on.contains(getPackageName() + "/");
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void accessibility() {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                openSettings();
            }
        }

        @JavascriptInterface
        public String url() {
            return homeUrl();
        }

        @JavascriptInterface
        public void toast(String m) {
            MainActivity.this.toast(m);
        }

        /** Liste des applis installées : [{"pkg":"...","label":"..."}] */
        @JavascriptInterface
        public String apps() {
            PackageManager pm = getPackageManager();
            JSONArray out = new JSONArray();
            Set<String> seen = new HashSet<>();
            String[] cats = Build.VERSION.SDK_INT >= 21
                    ? new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}
                    : new String[]{Intent.CATEGORY_LAUNCHER};
            for (String c : cats) {
                Intent q = new Intent(Intent.ACTION_MAIN).addCategory(c);
                List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
                for (ResolveInfo r : list) {
                    String pkg = r.activityInfo.packageName;
                    if (seen.contains(pkg) || pkg.equals(getPackageName())) continue;
                    seen.add(pkg);
                    try {
                        JSONObject o = new JSONObject();
                        o.put("pkg", pkg);
                        o.put("label", String.valueOf(r.loadLabel(pm)));
                        out.put(o);
                    } catch (Exception ignored) {
                    }
                }
            }
            return out.toString();
        }

        /** Icône d'une appli en image PNG (data:image/png;base64,...) */
        @JavascriptInterface
        public String icon(String pkg) {
            try {
                PackageManager pm = getPackageManager();
                Drawable d = null;
                if (Build.VERSION.SDK_INT >= 21) {
                    try { d = pm.getApplicationBanner(pkg); } catch (Exception ignored) { }
                }
                if (d == null) d = pm.getApplicationIcon(pkg);
                int w = Math.max(1, Math.min(320, d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : 160));
                int h = Math.max(1, Math.min(320, d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : 160));
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                d.setBounds(0, 0, w, h);
                d.draw(c);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.PNG, 100, bos);
                return "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }
    }
}
