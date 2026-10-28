package com.eafnv.callforward.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.eafnv.callforward.mmi.AutoSwitchRunner;
import com.eafnv.callforward.mmi.AutoSwitchScheduler;
import com.eafnv.callforward.util.AppPrefs;

/**
 * 每日定时触发：执行一次当日自动转移，并排下一天。
 *
 * 闹钟是单次的，所以进来先重排下一天，再异步执行，
 * 避免执行耗时或失败把第二天的触发也一起拖没。
 */
public class AutoSwitchReceiver extends BroadcastReceiver {

    public static final String ACTION_DAILY = "com.eafnv.callforward.action.DAILY_SWITCH";

    /** 未开启自动转移时只取消闹钟；否则重排下一天并异步执行。 */
    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        if (!AppPrefs.autoEnabled(app)) {
            AutoSwitchScheduler.cancel(app);
            return;
        }

        // 单次闹钟，触发后立刻排下一天
        AutoSwitchScheduler.schedule(app);

        final PendingResult result = goAsync();
        new Thread(() -> {
            try {
                AutoSwitchRunner.runIfDue(app);
            } finally {
                result.finish();
            }
        }, "duty-auto-switch").start();
    }
}
