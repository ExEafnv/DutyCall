package com.eafnv.callforward.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.eafnv.callforward.mmi.AutoSwitchScheduler;
import com.eafnv.callforward.mmi.MmiExecutor;
import com.eafnv.callforward.util.AppPrefs;

/**
 * 开机与覆盖安装后的自愈入口。
 *
 * 闹钟在重启后会丢失，覆盖安装又会杀掉常驻服务，两种情况都在这里重新排上。
 */
public class BootReceiver extends BroadcastReceiver {

    /** 只处理开机完成与覆盖安装两种广播。 */
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        // 开机后闹钟会丢失；应用被覆盖安装后服务也会被杀掉，两种情况都要恢复
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!boot && !updated) return;

        Context app = context.getApplicationContext();
        if (!AppPrefs.autoEnabled(app)) return;

        try {
            AutoSwitchScheduler.schedule(app);
        } catch (Exception ignored) {
            // 定时排不上时，用户下次打开 App 会重新排
        }
        try {
            MmiExecutor.startAgent(app);
        } catch (Exception ignored) {
            // 后台启动前台服务受限时，等用户打开 App 或下次定时触发再恢复
        }
    }
}
