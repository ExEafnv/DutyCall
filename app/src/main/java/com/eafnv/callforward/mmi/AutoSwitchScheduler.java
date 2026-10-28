package com.eafnv.callforward.mmi;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.eafnv.callforward.MainActivity;
import com.eafnv.callforward.receiver.AutoSwitchReceiver;
import com.eafnv.callforward.util.AppPrefs;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 每日自动转移的定时调度。
 *
 * 用 setAlarmClock 而不是 setExactAndAllowWhileIdle：前者在系统看来是“用户可见的闹钟”，
 * 不会被 Doze 与厂商冻结策略延后，代价是状态栏常驻一个闹钟图标；
 * 没有精确闹钟权限时才退回 setAndAllowWhileIdle，接受触发时间的偏差。
 *
 * 每次只排下一次；触发后由 {@link AutoSwitchReceiver} 执行并重新排下一天。
 */
public final class AutoSwitchScheduler {

    private static final int REQUEST_CODE = 3001;

    private AutoSwitchScheduler() {
    }

    /** 重排下一次触发：先取消旧闹钟避免重复；未开启自动转移时只取消不重排。 */
    public static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        PendingIntent pending = pendingIntent(ctx);
        am.cancel(pending);

        if (!AppPrefs.autoEnabled(ctx)) return;

        int hour = AppPrefs.autoHour(ctx);
        int minute = AppPrefs.autoMinute(ctx);
        long triggerAt = nextTriggerMillis(hour, minute);

        // Android 12 起精确闹钟需要用户授权，没有权限就退回不精确闹钟
        boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
        try {
            if (exact) {
                // setAlarmClock 是唯一不会被 Doze / 厂商冻结策略延后的闹钟类型，
                // 代价是状态栏会出现一个闹钟图标。
                PendingIntent showIntent = PendingIntent.getActivity(ctx, REQUEST_CODE,
                        new Intent(ctx, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerAt, showIntent), pending);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
            }
        } catch (SecurityException e) {
            // 权限在授权后被回收时 setAlarmClock 会抛异常，这里降级重排
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
        }
    }

    /** 取消已排的闹钟，退出自动转移时调用。 */
    public static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(pendingIntent(ctx));
    }

    /** 固定的 PendingIntent：排程与取消必须用同一个实例才能对上。 */
    private static PendingIntent pendingIntent(Context ctx) {
        Intent intent = new Intent(ctx, AutoSwitchReceiver.class);
        intent.setAction(AutoSwitchReceiver.ACTION_DAILY);
        return PendingIntent.getBroadcast(ctx, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 计算下一次触发时刻：今天的时刻已过则顺延到明天。 */
    private static long nextTriggerMillis(int hour, int minute) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now();
        LocalDateTime target = LocalDateTime.of(today, LocalTime.of(hour, minute));
        if (!target.isAfter(LocalDateTime.now())) {
            target = target.plusDays(1);
        }
        return target.atZone(zone).toInstant().toEpochMilli();
    }
}
