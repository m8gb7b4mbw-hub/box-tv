package fr.boxtv.nas;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Mon NAS : ouvre la page « Mon NAS » du serveur IPTV (dossiers, photos, vidéos du NAS).
 * Le bouton Retour est d'abord donné à la page (window.boxBack) ; à la racine, l'appli se ferme.
 */
public class MainActivity extends Activity {

    // Adresse Tailscale d'abord (comme Box TV), puis l'adresse locale du NAS.
    static final String[] URLS = {
            "http://100.99.4.38:8080/nas.html",
            "http://192.168.1.22:8080/nas.html"
    };

    WebView web;
    int tried = 0;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(s.getUserAgentString() + " BoxTV/1 MonNAS/1");
        CookieManager.getInstance().setAcceptCookie(true);   // le code PIN est retenu par un cookie
        web.addJavascriptInterface(new Bridge(), "BoxNAS");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return false;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) next();
            }
        });
        setContentView(web);
        web.loadUrl(URLS[0]);
    }

    // l'adresse ne répond pas : on essaie la suivante, puis on affiche « NAS injoignable »
    void next() {
        tried++;
        if (tried < URLS.length) {
            web.loadUrl(URLS[tried]);
            return;
        }
        String html = "<!doctype html><html><head><meta name=viewport content='width=device-width'>"
                + "<style>body{margin:0;background:#0b0b0e;color:#f5f5f7;font-family:sans-serif;display:flex;align-items:center;"
                + "justify-content:center;height:100vh;text-align:center}p{color:#a1a1a6}button{font-size:20px;margin-top:16px;"
                + "padding:14px 30px;border:0;border-radius:14px;background:#fff;color:#000}</style></head><body><div>"
                + "<h1>NAS injoignable</h1><p>Vérifie que le NAS est allumé et que Tailscale est connecté sur la box.</p>"
                + "<button autofocus onclick='BoxNAS.retry()'>Réessayer</button></div>"
                + "<script>document.querySelector('button').focus()</script></body></html>";
        web.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null);
    }

    class Bridge {
        @JavascriptInterface
        public String app() {
            return "nas";
        }

        @JavascriptInterface
        public void retry() {
            runOnUiThread(() -> {
                tried = 0;
                web.loadUrl(URLS[0]);
            });
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            event.startTracking();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.isTracking() && !event.isCanceled()) {
            web.evaluateJavascript("(window.boxBack&&window.boxBack())?'1':'0'", v -> {
                if (v == null || !v.contains("1")) finish();
            });
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
