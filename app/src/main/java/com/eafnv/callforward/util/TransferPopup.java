package com.eafnv.callforward.util;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import com.eafnv.callforward.MainActivity;
import com.eafnv.callforward.R;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 「已转移到 X」浮层提示。
 *
 * 通知横幅在部分国产 ROM（如 Flyme）上并不可靠，即使渠道是高优先级、
 * 系统里的「悬浮通知」也开着，也可能只是静静躺在通知栏里不弹。
 * 这里直接用应用已有的悬浮窗权限画一张小卡片，保证用户能立刻看到换人结果。
 *
 * 淡入淡出与上下位移都作用在**窗口本身**（{@link WindowManager.LayoutParams#alpha} 与 {@code y}），
 * 不是 View 属性：悬浮窗上 View 级 alpha 动画经常只生效一帧，看起来像瞬间消失。
 *
 * 两个平台坑（都实际踩过）：
 * <ul>
 *   <li>不能加 {@code FLAG_NOT_TOUCHABLE}：Flyme 会把「不可触摸」的悬浮窗强制压到 80% 不透明度，
 *       卡片会一直透出背景；</li>
 *   <li>应用在后台时动画可能只跑几帧就停住，所以入场与退场各自带一个定时兜底，
 *       保证窗口最终一定是不透明、且一定会被移除。</li>
 * </ul>
 *
 * 没有悬浮窗权限时静默跳过，由通知栏里的那条通知兜底。
 */
public final class TransferPopup {

    /** 停留时长，到点开始淡出。 */
    private static final long DURATION_MS = 4_000L;
    /** 入场时长：位移要和淡入同步做完，太短会看成"闪现"。 */
    private static final long FADE_IN_MS = 280L;
    /** 淡出时长，与入场对称。 */
    private static final long FADE_OUT_MS = 280L;
    /**
     * 入场起点透明度。
     *
     * 不从 0 开始：悬浮窗的 alpha 逐帧合成，0→1 会有一长段"半透明影子"的过程，
     * 从 0.6 起步既能看清是从上面落下来的，又不会显得发虚。
     */
    private static final float FADE_IN_FROM = 0.6f;

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static View showing;
    private static WindowManager.LayoutParams showingParams;
    private static WindowManager showingWm;
    private static ValueAnimator fadeAnimator;
    private static Runnable dismissTask;

    private TransferPopup() {
    }

    /** 在屏幕上方弹出一次提示；重复调用会先撤掉上一条。 */
    public static void show(Context context, String personName) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        if (!AppPrefs.popupEnabled(app)) return;      // 用户关掉了悬浮窗提示
        if (!Settings.canDrawOverlays(app)) return;   // 没给权限就只留系统通知

        final WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;

        dismissNow();

        // 应用上下文默认拿不到 Material 主题，这里显式套一层，否则 MaterialCardView 会 inflate 失败
        android.view.ContextThemeWrapper themed =
                new android.view.ContextThemeWrapper(app, R.style.Theme_DutyCall);
        View card = LayoutInflater.from(themed).inflate(R.layout.view_transfer_popup, null);
        TextView title = card.findViewById(R.id.tv_popup_title);
        TextView sub = card.findViewById(R.id.tv_popup_sub);

        String name = personName == null || personName.isEmpty() ? "今日值班人" : personName;
        title.setText("已转移到 " + name);
        sub.setText("呼叫转移已生效 · " + LocalTime.now().format(HHMM));

        int width = (int) (app.getResources().getDisplayMetrics().widthPixels * 0.92f);
        final int topY = dp(app, 56);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // 注意：不能加 FLAG_NOT_TOUCHABLE。
                // 实测 Flyme 会把「不可触摸」的悬浮窗强制压到 80% 不透明度，
                // 卡片会一直透出背景；去掉这个标志才是真正不透明的。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        // 首帧就放在"上方偏移位"，避免动画启动前先闪一帧在终点位置
        final int slideIn = dp(app, 40);
        lp.y = topY - slideIn;
        lp.alpha = FADE_IN_FROM;    // 直接以起点透明度出现，再淡入

        try {
            wm.addView(card, lp);
        } catch (Exception ignored) {
            return;
        }
        showing = card;
        showingParams = lp;
        showingWm = wm;

        // 点卡片回到 App（窗口可触摸，正好用上这次点击）
        card.setOnClickListener(v -> {
            dismissNow();
            Intent i = new Intent(app, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            app.startActivity(i);
        });

        // 入场：窗口 alpha 抬升 + 从上方 24dp 落到原位
        animateWindow(FADE_IN_FROM, 1f, topY - slideIn, topY, FADE_IN_MS, null);

        // 兜底：应用在后台时动画可能只跑几帧就停住，窗口会一直停在半透明状态。
        // 到点后无条件把 alpha 钉成 1，保证卡片是实心的。
        MAIN.postDelayed(() -> {
            if (showing != card) return;
            lp.alpha = 1f;
            try {
                wm.updateViewLayout(card, lp);
            } catch (Exception ignored) {
            }
        }, FADE_IN_MS + 100);

        dismissTask = () -> dismissWithFade();
        MAIN.postDelayed(dismissTask, DURATION_MS);
    }

    /** 淡出，结束后移除窗口。 */
    private static void dismissWithFade() {
        if (dismissTask != null) {
            MAIN.removeCallbacks(dismissTask);
            dismissTask = null;
        }
        if (showing == null) return;
        final View v = showing;
        final WindowManager wm = showingWm;

        // 退场：向上收回 28dp 的同时淡出，和入场对称。
        // 注意必须在把 showing 置空**之前**启动动画：animateWindow 依赖它判断窗口还在不在。
        final int slideOut = dp(v.getContext(), 28);
        final int fromY = showingParams.y;
        animateWindow(1f, 0f, fromY, fromY - slideOut, FADE_OUT_MS, null);
        showing = null;

        // 兜底：动画在后台可能只跑几帧就停住，到点无论如何都要把窗口撤掉
        MAIN.postDelayed(() -> {
            if (fadeAnimator != null) {
                fadeAnimator.cancel();
                fadeAnimator = null;
            }
            try {
                wm.removeView(v);
            } catch (Exception ignored) {
            }
        }, FADE_OUT_MS + 120);
    }

    /** 立刻移除，不做动画（被新的提示顶替时用）。 */
    private static void dismissNow() {
        if (dismissTask != null) {
            MAIN.removeCallbacks(dismissTask);
            dismissTask = null;
        }
        if (fadeAnimator != null) {
            fadeAnimator.cancel();
            fadeAnimator = null;
        }
        if (showing == null) return;
        View v = showing;
        WindowManager wm = showingWm;
        showing = null;
        try {
            wm.removeView(v);
        } catch (Exception ignored) {
        }
    }

    /**
     * 动画化窗口的 alpha 与纵向位置。
     *
     * @param onEnd 动画结束回调，没有则传 null
     */
    private static void animateWindow(float fromAlpha, float toAlpha,
                                      int fromY, int toY, long duration, Runnable onEnd) {
        if (showing == null || showingParams == null || showingWm == null) return;
        if (fadeAnimator != null) {
            fadeAnimator.cancel();
            fadeAnimator = null;
        }
        final WindowManager.LayoutParams lp = showingParams;
        final WindowManager wm = showingWm;
        final View v = showing;

        fadeAnimator = ValueAnimator.ofFloat(0f, 1f);
        fadeAnimator.setDuration(duration);
        fadeAnimator.setInterpolator(new DecelerateInterpolator());
        fadeAnimator.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            lp.alpha = fromAlpha + (toAlpha - fromAlpha) * f;
            lp.y = (int) (fromY + (toY - fromY) * f);
            try {
                wm.updateViewLayout(v, lp);
            } catch (Exception ignored) {
            }
        });
        fadeAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                fadeAnimator = null;
                if (onEnd != null) onEnd.run();
            }
        });
        fadeAnimator.start();
    }

    private static int dp(Context ctx, int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }
}
