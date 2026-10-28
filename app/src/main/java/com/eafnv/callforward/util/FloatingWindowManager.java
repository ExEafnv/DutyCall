package com.eafnv.callforward.util;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import com.eafnv.callforward.MainActivity;
import com.eafnv.callforward.R;

/**
 * 常驻悬浮图标：圆形、半透明、可拖动、松手自动贴边、点击回到 App。
 *
 * 两个系统约束：贴边后图标落在边缘返回手势区，必须用 setSystemGestureExclusionRects
 * 声明排除，否则从图标起手的拖动会被系统当成“返回”；贴边待命时降低不透明度，尽量不挡内容。
 *
 * 悬浮窗只是“看得见、能一键回来”的体验层，缺权限时静默降级，不影响后台自动转移。
 */
public class FloatingWindowManager {

    // ===== 尺寸与动效参数 =====

    /** 拖动中或不在边缘时的不透明度。 */
    private static final float ALPHA_ACTIVE = 0.88f;
    /** 贴边待命时的不透明度，尽量不挡内容。 */
    private static final float ALPHA_DOCKED = 0.5f;
    /** 透明度渐变时长（毫秒）。 */
    private static final long ALPHA_FADE_MS = 180L;
    /** 图标边长（dp）。 */
    private static final int SIZE_DP = 44;
    /** 轻点与拖动的判定阈值（dp）。 */
    private static final int TAP_SLOP_DP = 6;
    /** 贴边动画时长（毫秒）。 */
    private static final long SNAP_DURATION_MS = 220L;
    /** 长按判定时长（毫秒）。 */
    private static final long LONG_PRESS_MS = 520L;

    private final Context context;
    private final WindowManager windowManager;
    private final float density;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private ImageView icon;
    private WindowManager.LayoutParams params;
    private ValueAnimator snapAnimator;

    /** 长按悬浮球时的回调，用于快速记录日志。 */
    public interface OnLongPressListener {
        void onLongPress();
    }

    private OnLongPressListener longPressListener;

    /** 注册长按回调；未注册时长按不产生效果。 */
    public void setOnLongPressListener(OnLongPressListener listener) {
        this.longPressListener = listener;
    }

    /** 持有 ApplicationContext，避免悬浮窗泄漏 Activity。 */
    public FloatingWindowManager(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.density = this.context.getResources().getDisplayMetrics().density;
    }

    // ===== 显示与隐藏 =====

    /** 是否具备悬浮窗权限；缺权限时整个悬浮层静默降级。 */
    public boolean canShow() {
        return windowManager != null
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context));
    }

    /** 当前是否已显示。 */
    public boolean isShowing() {
        return icon != null;
    }

    /**
     * 显示悬浮图标：恢复上次位置、按贴边状态设置透明度、声明手势排除区域。
     *
     * 缺权限或 addView 失败时静默返回，不影响后台自动转移。
     */
    public void show() {
        if (icon != null || !canShow()) return;

        int size = dp(SIZE_DP);

        icon = new ImageView(context);
        icon.setImageDrawable(circularIcon(size));
        icon.setAlpha(ALPHA_ACTIVE);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                size, size, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        // 默认就贴右边，避免一上来压住内容
        params.x = AppPrefs.get(context).getInt(AppPrefs.OVERLAY_X, metrics.widthPixels - size);
        params.y = AppPrefs.get(context).getInt(AppPrefs.OVERLAY_Y, Math.round(metrics.heightPixels * 0.42f));
        params.x = clampX(params.x);
        params.y = clampY(params.y);

        icon.setOnTouchListener(new DragTouchListener());

        try {
            windowManager.addView(icon, params);
        } catch (Exception e) {
            icon = null;
            return;
        }

        // 恢复上次位置后，如果是贴边的就淡一点
        applyAlphaForPosition(false);

        // 图标贴边后正好落在系统的边缘返回手势区里，
        // 不声明排除区域的话，从这里起手的拖动会被系统当成“返回”，
        // 所以按当前图标大小把这块矩形排除掉。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            icon.post(() -> {
                if (icon == null) return;
                icon.setSystemGestureExclusionRects(java.util.Collections.singletonList(
                        new android.graphics.Rect(0, 0, icon.getWidth(), icon.getHeight())));
            });
        }
    }

    /** 移除悬浮窗并取消进行中的贴边动画。 */
    public void hide() {
        cancelSnap();
        if (icon == null) return;
        try {
            windowManager.removeView(icon);
        } catch (Exception ignored) {
            // 已被系统移除
        }
        icon = null;
    }

    // ===== 外观 =====

    /** 把 App 图标裁成圆形，避免方形贴纸压在桌面上。 */
    private Drawable circularIcon(int sizePx) {
        Drawable source = ContextCompat.getDrawable(context, R.mipmap.ic_launcher);
        if (source == null) return null;

        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        source.setBounds(0, 0, sizePx, sizePx);
        source.draw(canvas);

        RoundedBitmapDrawable rounded = RoundedBitmapDrawableFactory.create(context.getResources(), bitmap);
        rounded.setCircular(true);
        return rounded;
    }

    // ===== 点击与贴边 =====

    /** 点击图标回到 App，后台启动受限时静默失败。 */
    private void openApp() {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            context.startActivity(intent);
        } catch (Exception ignored) {
            // 后台启动受限时静默失败
        }
    }

    /** 松手后吸附到最近的左右边缘。 */
    private void snapToEdge() {
        if (icon == null) return;
        int screenWidth = windowManager.getDefaultDisplay().getWidth();
        boolean toLeft = params.x + params.width / 2 < screenWidth / 2;
        int target = toLeft ? 0 : screenWidth - params.width;
        animateTo(target);
    }

    /** 平移到目标位置，结束后落盘并刷新透明度。 */
    private void animateTo(final int targetX) {
        cancelSnap();
        final int startX = params.x;
        if (startX == targetX) {
            persistPosition();
            applyAlphaForPosition(true);
            return;
        }
        snapAnimator = ValueAnimator.ofInt(startX, targetX);
        snapAnimator.setDuration(SNAP_DURATION_MS);
        snapAnimator.addUpdateListener(animator -> {
            if (icon == null) return;
            params.x = (int) animator.getAnimatedValue();
            try {
                windowManager.updateViewLayout(icon, params);
            } catch (Exception ignored) {
            }
        });
        snapAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                persistPosition();
                applyAlphaForPosition(true);
            }
        });
        snapAnimator.start();
    }

    // ===== 位置与透明度 =====

    /** 贴边时淡一些，拖起来时恢复清晰。 */
    private void applyAlphaForPosition(boolean animate) {
        if (icon == null || params == null) return;
        float target = isDocked(params.x) ? ALPHA_DOCKED : ALPHA_ACTIVE;
        if (Math.abs(icon.getAlpha() - target) < 0.01f) return;
        if (animate) {
            icon.animate().alpha(target).setDuration(ALPHA_FADE_MS).start();
        } else {
            icon.setAlpha(target);
        }
    }

    /** 判断是否贴在左右边缘（含 1dp 容差）。 */
    private boolean isDocked(int x) {
        if (params == null || windowManager == null) return false;
        int screen = windowManager.getDefaultDisplay().getWidth();
        int tolerance = dp(1);
        return x <= tolerance || x >= screen - params.width - tolerance;
    }

    /** 取消进行中的贴边动画。 */
    private void cancelSnap() {
        if (snapAnimator != null) {
            snapAnimator.cancel();
            snapAnimator = null;
        }
    }

    /** 位置落盘，下次启动恢复到原处。 */
    private void persistPosition() {
        if (params == null) return;
        AppPrefs.get(context).edit()
                .putInt(AppPrefs.OVERLAY_X, params.x)
                .putInt(AppPrefs.OVERLAY_Y, params.y)
                .apply();
    }

    /** dp 转像素。 */
    private int dp(int value) {
        return Math.round(value * density);
    }

    /** 把横坐标限制在屏幕内，避免拖出可视区。 */
    private int clampX(int x) {
        int screen = windowManager.getDefaultDisplay().getWidth();
        return Math.max(0, Math.min(x, Math.max(0, screen - params.width)));
    }

    /** 把纵坐标限制在屏幕内，避免拖出可视区。 */
    private int clampY(int y) {
        int screen = windowManager.getDefaultDisplay().getHeight();
        return Math.max(0, Math.min(y, Math.max(0, screen - params.height)));
    }

    // ===== 触摸处理 =====

    /** 拖动、轻点与长按的触摸处理：位移超过阈值才算拖动。 */
    private class DragTouchListener implements View.OnTouchListener {

        private int startX;
        private int startY;
        private float downRawX;
        private float downRawY;
        private boolean dragging;
        /** 本次按下是否已经触发过长按，避免松手时又当成轻点。 */
        private boolean longPressed;

        /** 计时到点后触发长按回调，并给一次短震动作为反馈。 */
        private final Runnable longPressRunnable = () -> {
            if (dragging || icon == null) return;
            longPressed = true;
            vibrateShort();
            if (longPressListener != null) longPressListener.onLongPress();
        };

        /** 按下变清晰、拖动跟手、长按记日志、松手贴边或轻点打开 App。 */
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cancelSnap();
                    // 手指按上去先变清晰，避免半透明看不清
                    if (icon != null) {
                        icon.animate().alpha(ALPHA_ACTIVE).setDuration(ALPHA_FADE_MS).start();
                    }
                    startX = params.x;
                    startY = params.y;
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    dragging = false;
                    longPressed = false;
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    int dx = (int) (event.getRawX() - downRawX);
                    int dy = (int) (event.getRawY() - downRawY);
                    if (!dragging && (Math.abs(dx) > dp(TAP_SLOP_DP) || Math.abs(dy) > dp(TAP_SLOP_DP))) {
                        dragging = true;
                        // 一旦开始拖动，长按就不再成立
                        handler.removeCallbacks(longPressRunnable);
                    }
                    if (dragging) {
                        params.x = clampX(startX + dx);
                        params.y = clampY(startY + dy);
                        try {
                            windowManager.updateViewLayout(icon, params);
                        } catch (Exception ignored) {
                        }
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPressRunnable);
                    if (dragging) {
                        snapToEdge();
                    } else if (longPressed) {
                        // 长按已经处理过，松手不再打开 App
                        applyAlphaForPosition(true);
                    } else {
                        // 轻点：恢复待命透明度后再打开 App
                        applyAlphaForPosition(true);
                        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                            openApp();
                        }
                    }
                    return true;

                default:
                    return false;
            }
        }
    }

    /** 长按反馈用的短震动；设备不支持或无权限时静默忽略。 */
    private void vibrateShort() {
        try {
            android.os.Vibrator vibrator =
                    (android.os.Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(
                        25, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(25);
            }
        } catch (Exception ignored) {
            // 没权限就跳过，不影响长按本身
        }
    }
}
