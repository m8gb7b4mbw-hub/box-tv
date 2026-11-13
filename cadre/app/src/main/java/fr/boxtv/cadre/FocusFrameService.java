package fr.boxtv.cadre;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Cadre gris autour de l'élément qui a le focus (sélection à la télécommande),
 * uniquement dans les applis listées dans TARGETS. Ailleurs, le cadre est caché.
 *
 * Apple Music : la flèche du haut ne remonte jamais des playlists vers la barre d'onglets
 * (Accueil, Nouveautés, Radio, Bibliothèque). Quand un appui sur Haut ne déplace pas la sélection,
 * on la place sur l'onglet de la page affichée. Les touches ne sont jamais bloquées.
 * Ne lit ni n'envoie aucune donnée personnelle.
 */
public class FocusFrameService extends AccessibilityService {

    private static final String MUSIC = "com.apple.android.music";
    private static final String[] TARGETS = { MUSIC };
    // Pages d'Apple Music, dans l'ordre des onglets de la barre du haut.
    private static final String[] PAGES = {
            MUSIC + ":id/top_level_home_page", MUSIC + ":id/top_level_new_page",
            MUSIC + ":id/top_level_radio_page", MUSIC + ":id/top_level_library_page" };
    private static final String DRAWER_ITEM = MUSIC + ":id/design_menu_item_text";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() { public void run() { update(); } };
    // Deuxième passage, après la fin des animations de défilement.
    private final Runnable refreshLate = new Runnable() { public void run() { update(); } };
    private final Runnable checkUp = new Runnable() { public void run() { unstickUp(); } };

    private WindowManager wm;
    private View frame;
    private WindowManager.LayoutParams lp;
    private boolean shown;
    private boolean inTarget;
    private final Rect r = new Rect();
    private final Rect beforeUp = new Rect();
    private boolean haveBeforeUp;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        frame = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setStroke(px(5), 0xFFC7C7CC); // gris clair
        d.setCornerRadius(px(16));
        frame.setBackground(d);
        lp = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.setTitle("Cadre de sélection");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        scheduleRefresh();
    }

    private void scheduleRefresh() {
        handler.removeCallbacks(refresh);
        handler.removeCallbacks(refreshLate);
        handler.postDelayed(refresh, 30);
        handler.postDelayed(refreshLate, 350);
    }

    /** Observe seulement : renvoie toujours false, la touche continue vers l'appli. */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (!inTarget || event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int k = event.getKeyCode();
        if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            scheduleRefresh();
            handler.postDelayed(refresh, 120);
        }
        if (k == KeyEvent.KEYCODE_DPAD_UP && event.getRepeatCount() == 0) {
            haveBeforeUp = focusedBounds(beforeUp);
            handler.removeCallbacks(checkUp);
            if (haveBeforeUp) handler.postDelayed(checkUp, 260);
        }
        return false;
    }

    /** Après un appui sur Haut : si la sélection n'a pas bougé, la placer sur l'onglet de la page affichée. */
    private void unstickUp() {
        Rect now = new Rect();
        if (!haveBeforeUp || !focusedBounds(now) || !now.equals(beforeUp)) return;
        if (now.top < px(30) && now.width() < px(700)) return; // déjà dans la barre du haut
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !MUSIC.contentEquals(String.valueOf(root.getPackageName()))) return;
        if (visible(root.findAccessibilityNodeInfosByViewId(DRAWER_ITEM))) return; // menu latéral ouvert
        List<AccessibilityNodeInfo> tabs = new ArrayList<>();
        collectTabs(root, tabs, 0);
        if (tabs.isEmpty()) return;
        Collections.sort(tabs, new Comparator<AccessibilityNodeInfo>() {
            public int compare(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
                Rect ra = new Rect(), rb = new Rect();
                a.getBoundsInScreen(ra); b.getBoundsInScreen(rb);
                return ra.left - rb.left;
            }
        });
        int page = 0;
        for (int i = 0; i < PAGES.length; i++)
            if (visible(root.findAccessibilityNodeInfosByViewId(PAGES[i]))) { page = i; break; }
        AccessibilityNodeInfo tab = tabs.get(Math.min(page, tabs.size() - 1));
        tab.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        scheduleRefresh();
    }

    /** Onglets de la barre du haut : éléments sélectionnables tout en haut de l'écran, avec un texte. */
    private void collectTabs(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 40) return;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        if (n.isFocusable() && n.isClickable() && n.isVisibleToUser()
                && b.top < px(30) && b.bottom <= px(150) && b.width() < px(700) && hasText(n)) {
            out.add(n);
            return;
        }
        for (int i = 0; i < n.getChildCount(); i++) collectTabs(n.getChild(i), out, depth + 1);
    }

    private static boolean hasText(AccessibilityNodeInfo n) {
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null && c.getText() != null && c.getText().length() > 0) return true;
        }
        return false;
    }

    private static boolean visible(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null) return false;
        for (AccessibilityNodeInfo n : nodes) if (n != null && n.isVisibleToUser()) return true;
        return false;
    }

    private boolean focusedBounds(Rect out) {
        try {
            AccessibilityNodeInfo n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (n == null || !isTarget(n.getPackageName())) return false;
            n.getBoundsInScreen(out);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void update() {
        if (wm == null) return;
        AccessibilityNodeInfo n = null;
        try {
            n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            inTarget = n != null && isTarget(n.getPackageName());
            if (!inTarget) { hide(); return; }
            n.getBoundsInScreen(r);
        } catch (RuntimeException e) {
            hide();
            return;
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean whole = r.width() >= dm.widthPixels - 4 && r.height() >= dm.heightPixels - 4;
        if (r.width() <= 0 || r.height() <= 0 || whole) { hide(); return; }
        int pad = px(2);
        lp.x = r.left - pad;
        lp.y = r.top - pad;
        lp.width = r.width() + 2 * pad;
        lp.height = r.height() + 2 * pad;
        try {
            if (!shown) { wm.addView(frame, lp); shown = true; }
            else wm.updateViewLayout(frame, lp);
        } catch (RuntimeException e) {
            shown = false;
        }
    }

    private void hide() {
        if (!shown) return;
        try { wm.removeView(frame); } catch (RuntimeException ignored) { }
        shown = false;
    }

    private static boolean isTarget(CharSequence pkg) {
        if (pkg == null) return false;
        for (String t : TARGETS) if (t.contentEquals(pkg)) return true;
        return false;
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        hide();
        super.onDestroy();
    }
}
