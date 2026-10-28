package com.eafnv.callforward.service;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;

import androidx.core.content.ContextCompat;

import com.eafnv.callforward.mmi.AutoSwitchRunner;
import com.eafnv.callforward.mmi.AutoSwitchScheduler;
import com.eafnv.callforward.mmi.MmiExecutor;
import com.eafnv.callforward.util.AppPrefs;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 解锁触发器：借无障碍服务拿到“用户解锁”这个时机。
 *
 * 国产 ROM 会在息屏后冻结普通后台进程，定时闹钟和解锁广播都可能收不到；
 * 无障碍服务由系统绑定，优先级高于普通后台服务，通常不会被冻结，因此用它兜底检查当天排班。
 *
 * 本服务不读取屏幕内容（canRetrieveWindowContent=false），只消费窗口状态事件，
 * 用来判断锁屏是否已经解除。
 */
public class DutyAccessibilityService extends AccessibilityService {

    /** 两次检查之间的最小间隔，避免频繁解锁导致重复请求。 */
    private static final long MIN_INTERVAL_MS = 30_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private long lastCheckElapsed;
    private BroadcastReceiver unlockReceiver;

    /** 服务连接后注册广播，并顺手恢复常驻代理与定时。 */
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        registerUnlockReceiver();

        // 服务被系统拉起时顺手恢复常驻代理与定时
        if (AppPrefs.autoEnabled(this)) {
            MmiExecutor.startAgent(this);
            AutoSwitchScheduler.schedule(this);
        }
        checkNow(true);
    }

    /** 只关心窗口状态变化，用它判断锁屏是否解除。 */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }
        checkNow(false);
    }

    /** 本服务不提供朗读等交互，无需处理中断。 */
    @Override
    public void onInterrupt() {
    }

    /** 服务销毁时注销广播并停掉工作线程。 */
    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterUnlockReceiver();
        io.shutdownNow();
    }

    /** 解绑时同样要注销广播，避免重复注册。 */
    @Override
    public boolean onUnbind(Intent intent) {
        unregisterUnlockReceiver();
        return super.onUnbind(intent);
    }

    // ===== 解锁检查 =====

    /**
     * 检查今天是否该自动转移。
     *
     * 锁屏未解除时直接返回，避免息屏期间白跑；
     * 非强制调用还有最小间隔限制，防止解锁瞬间的连续事件打满数据库查询。
     *
     * @param force 为 true 时跳过时间间隔限制（服务启动、解锁广播使用）
     */
    private void checkNow(boolean force) {
        if (!AppPrefs.autoEnabled(this)) return;

        KeyguardManager keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isKeyguardLocked()) {
            // 还锁着，等解锁后再来
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastCheckElapsed < MIN_INTERVAL_MS) return;
        lastCheckElapsed = now;

        io.execute(() -> {
            try {
                AutoSwitchRunner.runIfDue(getApplicationContext());
            } catch (Exception ignored) {
                // 补执行失败不影响服务本身存活
            }
        });
    }

    /** 注册解锁广播，作为无障碍事件的补充。 */
    private void registerUnlockReceiver() {
        if (unlockReceiver != null) return;
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                checkNow(true);
            }
        };
        try {
            ContextCompat.registerReceiver(this, unlockReceiver,
                    new IntentFilter(Intent.ACTION_USER_PRESENT),
                    ContextCompat.RECEIVER_EXPORTED);
        } catch (Exception e) {
            unlockReceiver = null;
        }
    }

    /** 注销解锁广播；未注册时直接返回，重复调用安全。 */
    private void unregisterUnlockReceiver() {
        if (unlockReceiver == null) return;
        try {
            unregisterReceiver(unlockReceiver);
        } catch (Exception ignored) {
        }
        unlockReceiver = null;
    }

    // ===== 系统开关查询 =====

    /** 判断本应用的无障碍服务是否已在系统中开启。 */
    public static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;

        ComponentName target = new ComponentName(context, DutyAccessibilityService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            ComponentName component = ComponentName.unflattenFromString(splitter.next());
            if (target.equals(component)) return true;
        }
        return false;
    }
}
