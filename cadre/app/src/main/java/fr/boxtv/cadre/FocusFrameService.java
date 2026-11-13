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
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Cadre bleu autour de l'élément qui a le focus (sélection à la télécommande),
 * uniquement dans les applis listées dans TARGETS. Ailleurs, le cadre est caché.
 * Ne lit aucun texte et n'envoie rien : seule la position de l'élément sélectionné est utilisée.
 */
public class FocusFrameService extends AccessibilityService {

    private static final String[] TARGETS = { "com.apple.android.music" };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() { public void run() { update(); } };
    // Deuxième passage, après la fin des animations de défilement.
    private final Runnable refreshLate = new Runnable() { public void run() { update(); } };

    private WindowManager wm;
    private View frame;
    private WindowManager.LayoutParams lp;
    private boolean shown;
    private final Rect r = new Rect();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        frame = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setStroke(px(5), 0xFF0A84FF); // bleu Apple
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
        handler.removeCallbacks(refresh);
        handler.removeCallbacks(refreshLate);
        handler.postDelayed(refresh, 30);
        handler.postDelayed(refreshLate, 350);
    }

    private void update() {
        if (wm == null) return;
        AccessibilityNodeInfo n = null;
        try {
            n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (n == null || !isTarget(n.getPackageName())) { hide(); return; }
            n.getBoundsInScreen(r);
        } catch (RuntimeException e) {
            hide();
            return;
        } finally {
            if (n != null) try { n.recycle(); } catch (RuntimeException ignored) { }
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
