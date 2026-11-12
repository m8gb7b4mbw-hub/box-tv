package fr.boxtv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Remplace l'accueil Google TV / Xiaomi : dès que l'accueil d'origine s'affiche
 * (démarrage de la box ou bouton Accueil), on ouvre Box TV à la place.
 * À activer dans Paramètres > Système > Accessibilité > Box TV.
 */
public class HomeWatcher extends AccessibilityService {

    static final Set<String> LAUNCHERS = new HashSet<>(Arrays.asList(
            "com.google.android.apps.tv.launcherx",   // Google TV
            "com.google.android.tvlauncher",          // Android TV
            "com.google.android.leanbacklauncher",
            "com.mitv.tvhome",                        // Xiaomi PatchWall
            "com.mitv.tvhome.atv",
            "com.android.tv.launcher"
    ));

    long last = 0;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = e.getPackageName();
        if (p == null || !LAUNCHERS.contains(p.toString())) return;
        if (!getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getBoolean("override", true)) return;
        long now = System.currentTimeMillis();
        if (now - last < 1500) return;
        last = now;
        Intent i = new Intent(this, MainActivity.class);
        i.setAction(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_HOME);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onInterrupt() {
    }
}
