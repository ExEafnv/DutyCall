package com.eafnv.callforward.mmi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.telephony.TelephonyManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.eafnv.callforward.MainActivity;
import com.eafnv.callforward.R;
import com.eafnv.callforward.util.AppPrefs;
import com.eafnv.callforward.util.FloatingWindowManager;

/**
 * MMI 指令的执行服务，负责前台服务形态与 USSD 回调链路。
 *
 * 两种形态：
 * - 常驻代理（agentMode=true）：用户开启后台自动转移后一直存活，持有常驻通知 + 悬浮窗；
 * - 一次性（agentMode=false）：前台点按钮时临时拉起，执行完即退出。
 *
 * 链路：调用方 → {@link MmiExecutor} 单飞闸门 → 发 USSD → 运营商回调 →
 * 写回 SharedPreferences（last_result / last_person 等）→ 刷新通知并广播。
 */
public class MmiService extends Service {

    private static final String TAG = "MmiService";

    private static final String CHANNEL_STATUS = "duty_call_status";
    /** 转移成功后的提示渠道，单独一个高优先级渠道，才能弹出横幅。 */
    private static final String CHANNEL_DONE = "duty_call_done";
    private static final int NOTIFICATION_ID = 2001;
    /** 成功提示的通知 id，与常驻状态通知分开，互不覆盖。 */
    private static final int NOTIFICATION_ID_DONE = 2002;
    private static final long USSD_TIMEOUT_MS = 20_000L;
    private static final long TRANSIENT_STOP_DELAY_MS = 1_200L;

    /** 执行结果：回执明确表示成功。 */
    private static final int OUTCOME_OK = 0;
    /** 执行结果：确认失败（失败码、超时、异常）。 */
    private static final int OUTCOME_FAIL = 1;
    /** 执行结果：已发出，但回执无法判定（看不懂的文案，或拨号模式）。 */
    private static final int OUTCOME_UNKNOWN = 2;

    /**
     * 回执里出现这些词就判定成功。
     *
     * 不同运营商说法差别很大，除了「设置成功」这类直白说法，
     * 还有「已激活 / 已生效 / 已启用 / 办理成功」等等，一并收进来。
     */
    private static final String[] SUCCESS_WORDS = {
            "成功", "已设置", "已登记", "已开通", "已办理", "已取消",
            "已激活", "已生效", "已启用", "已受理", "已变更", "已修改",
            // 中国移动的来电转接回执：注册是「来电转接\n已注册。」，取消是「来电转接\n已删除。」
            "已注册", "注册成功", "已删除", "已撤销"
    };
    /** 回执里出现这些词就判定失败；顺序放在成功词之后，避免误伤。 */
    private static final String[] FAILURE_WORDS = {
            "失败", "错误", "不支持", "无法", "不能", "未开通",
            "余额不足", "欠费", "繁忙", "稍后再试", "无效"
    };

    /** 二次判定：回执看不懂时，等一小会儿再看手机自己的呼叫转移状态。 */
    private static final long INDICATOR_CHECK_DELAY_MS = 1_500L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private FloatingWindowManager overlay;
    private boolean foregroundStarted;
    private boolean agentMode;
    private String statusText;
    private Task currentTask;
    private Runnable timeoutTask;
    private BroadcastReceiver unlockReceiver;

    /** 初始化渠道与悬浮窗；用户已开启自动转移时补回常驻形态。 */
    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        overlay = new FloatingWindowManager(this);
        // 长按悬浮球：快速记录今天的交接日志
        overlay.setOnLongPressListener(this::openQuickLog);
        agentMode = AppPrefs.autoEnabled(this);
        if (agentMode) {
            // 服务被任何途径拉起时，只要用户开着自动转移，就补回常驻形态
            if (overlay.canShow()) overlay.show();
            registerUnlockReceiver();
        }
    }

    /**
     * 按 action 分派：拉起 / 停止常驻代理、刷新通知、执行指令。
     *
     * 统一返回 START_NOT_STICKY：需要常驻时由开机广播或解锁检查重新拉起，
     * 不依赖系统重建一个状态不全的服务实例。
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!ensureForeground()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            if (!agentMode) stopSelf();
            return START_NOT_STICKY;
        }

        switch (action) {
            case MmiExecutor.ACTION_START_AGENT:
                enterAgentMode();
                break;
            case MmiExecutor.ACTION_STOP_AGENT:
                leaveAgentMode();
                return START_NOT_STICKY;
            case MmiExecutor.ACTION_REFRESH:
                refreshNotification();
                if (!agentMode) stopSelf();
                break;
            case MmiExecutor.ACTION_EXECUTE:
                handleExecute(intent);
                break;
            default:
                if (!agentMode) stopSelf();
                break;
        }
        return START_NOT_STICKY;
    }

    // ===== 生命周期 =====

    /** 确保进入前台；机型拒绝 phoneCall 类型时返回 false，由调用方停掉自己。 */
    private boolean ensureForeground() {
        if (foregroundStarted) return true;
        Notification notification = buildNotification();
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
            foregroundStarted = true;
            return true;
        } catch (Exception e) {
            // 机型不支持 phoneCall 类型前台服务时不再强撑
            statusText = "系统拒绝了后台服务";
            return false;
        }
    }

    /** 进入常驻代理：落盘开关、显示悬浮窗、注册解锁补偿、刷新通知。 */
    private void enterAgentMode() {
        agentMode = true;
        AppPrefs.setAutoEnabled(this, true);
        if (overlay.canShow()) overlay.show();
        registerUnlockReceiver();
        refreshNotification();
    }

    /** 退出常驻代理：清开关、撤闹钟与广播、隐藏悬浮窗，最后停服。 */
    private void leaveAgentMode() {
        agentMode = false;
        AppPrefs.setAutoEnabled(this, false);
        AutoSwitchScheduler.cancel(this);
        unregisterUnlockReceiver();
        if (overlay != null) overlay.hide();
        statusText = null;
        stopForegroundCompat();
        stopSelf();
    }

    /** 销毁时收尾；务必释放单飞闸门，否则后续指令会被永久挡住。 */
    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterUnlockReceiver();
        if (overlay != null) overlay.hide();
        if (timeoutTask != null) {
            handler.removeCallbacks(timeoutTask);
            timeoutTask = null;
        }
        if (currentTask != null) {
            currentTask = null;
            MmiExecutor.release();
        }
    }

    /** 不提供绑定。 */
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ===== 快捷日志 =====

    /**
     * 长按悬浮球时打开今天的日志编辑页。
     *
     * 后台启动界面需要悬浮窗权限（本应用已持有），失败时静默忽略。
     */
    private void openQuickLog() {
        try {
            Intent intent = com.eafnv.callforward.ui.LogEditActivity
                    .intent(this, java.time.LocalDate.now().toEpochDay());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception ignored) {
            // 系统拦截后台启动时不影响其它功能
        }
    }

    // ===== 执行 =====

    /**
     * 解析一次执行请求：校验指令码、抢占单飞闸门，再按模式分派到 USSD 或拨号。
     *
     * 抢不到闸门时不排队，只提示并刷新通知，本次请求直接丢弃。
     */
    private void handleExecute(Intent intent) {
        Task task = new Task();
        task.code = intent.getStringExtra(MmiExecutor.EXTRA_CODE);
        task.isSet = intent.getBooleanExtra(MmiExecutor.EXTRA_IS_SET, false);
        task.person = intent.getStringExtra(MmiExecutor.EXTRA_PERSON);
        task.phone = intent.getStringExtra(MmiExecutor.EXTRA_PHONE);
        task.manual = intent.getBooleanExtra(MmiExecutor.EXTRA_MANUAL, false);
        task.auto = intent.getBooleanExtra(MmiExecutor.EXTRA_AUTO, false);
        task.thenStop = !agentMode;

        if (task.code == null || task.code.isEmpty()) {
            if (task.thenStop) stopSelf();
            return;
        }

        if (!MmiExecutor.acquire()) {
            statusText = "上一个操作尚未结束，本次请求已忽略";
            refreshNotification();
            if (task.thenStop) handler.postDelayed(this::stopSelf, TRANSIENT_STOP_DELAY_MS);
            return;
        }

        currentTask = task;
        statusText = task.auto ? "正在自动执行…" : "正在执行…";
        refreshNotification();

        if (AppPrefs.isDialMode(this)) {
            executeDial(task);
        } else {
            executeUssd(task);
        }
    }

    /**
     * 静默模式：直接向运营商发 USSD，回调在主线程 Handler 上写回结果。
     *
     * 运营商可能长时间不回调，因此挂一个超时任务兜底，超时按失败处理并释放闸门。
     */
    private void executeUssd(Task task) {
        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (tm == null) {
            finish(task, OUTCOME_FAIL, "设备不支持通话功能");
            return;
        }
        try {
            tm.sendUssdRequest(task.code, new TelephonyManager.UssdResponseCallback() {
                @Override
                public void onReceiveUssdResponse(TelephonyManager telephonyManager, String request,
                                                  CharSequence response) {
                    super.onReceiveUssdResponse(telephonyManager, request, response);
                    String text = response == null ? "" : response.toString();
                    // 回执原文一定要留痕：不同运营商说法差异很大，出问题只能靠它定位
                    Log.i(TAG, "USSD 回执: " + (text.isEmpty() ? "(空)" : text));

                    int outcome = classifyUssd(text);
                    if (outcome == OUTCOME_UNKNOWN) {
                        // 回执看不懂时不要急着报「待确认」：
                        // 有些运营商只弹系统对话框、回调文本为空，但转移其实已经生效。
                        // 稍等一下用手机自身的呼叫转移状态做二次判定。
                        handler.postDelayed(() -> finishWithIndicator(task, text),
                                INDICATOR_CHECK_DELAY_MS);
                        return;
                    }
                    finish(task, outcome, text);
                }

                @Override
                public void onReceiveUssdResponseFailed(TelephonyManager telephonyManager, String request,
                                                        int failureCode) {
                    super.onReceiveUssdResponseFailed(telephonyManager, request, failureCode);
                    finish(task, OUTCOME_FAIL, "运营商返回失败码 " + failureCode);
                }
            }, handler);

            timeoutTask = () -> finish(task, OUTCOME_FAIL, "运营商无响应");
            handler.postDelayed(timeoutTask, USSD_TIMEOUT_MS);
        } catch (SecurityException e) {
            finish(task, OUTCOME_FAIL, "缺少拨号权限");
        } catch (Exception e) {
            finish(task, OUTCOME_FAIL, messageOf(e));
        }
    }

    /**
     * 回执看不懂时的二次判定：看手机自己认为「呼叫转移是否已开启」。
     *
     * 比猜文案可靠得多——只要转移真的生效了，系统的转移指示就是开着的。
     * 拿不到（老机型不支持、没权限）就保持「待确认」，不硬猜。
     *
     * @param text 原始回执，写进状态里方便用户核对
     */
    private void finishWithIndicator(Task task, String text) {
        if (task == null || currentTask != task) return;

        Boolean enabled = readCallForwardingIndicator();
        Log.i(TAG, "回执无法判定，呼叫转移状态=" + enabled);

        if (enabled != null && enabled == task.isSet) {
            // 想开且已开 / 想关且已关，都算达成目标
            finish(task, OUTCOME_OK, text);
        } else {
            finish(task, OUTCOME_UNKNOWN, text);
        }
    }

    /**
     * 读取系统的呼叫转移开关状态。
     *
     * 这个方法从 Android 12 起被废弃、新版 SDK 里已从公开接口移除，
     * 所以走反射调用：能拿到就用，拿不到返回 null，绝不影响主流程。
     *
     * @return true/false 为明确状态，null 表示这台机器给不出答案（不支持或被拒）
     */
    private Boolean readCallForwardingIndicator() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm == null) return null;
            java.lang.reflect.Method method =
                    TelephonyManager.class.getMethod("getCallForwardingIndicator");
            Object value = method.invoke(tm);
            return value instanceof Boolean ? (Boolean) value : null;
        } catch (Throwable t) {
            Log.i(TAG, "读不到呼叫转移状态：" + t.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 按回执文案判断成败。
     *
     * 先匹配成功词再匹配失败词，都不命中就记为“待确认”——
     * 宁可提示用户核实，也不要凭空宣布成功。
     */
    private static int classifyUssd(String response) {
        if (response == null) return OUTCOME_UNKNOWN;
        String text = response.replace(" ", "").replace("\u00a0", "");
        if (text.isEmpty()) return OUTCOME_UNKNOWN;

        for (String word : SUCCESS_WORDS) {
            if (text.contains(word)) return OUTCOME_OK;
        }
        for (String word : FAILURE_WORDS) {
            if (text.contains(word)) return OUTCOME_FAIL;
        }
        return OUTCOME_UNKNOWN;
    }

    /**
     * 兼容模式：拉起系统拨号执行 MMI。
     * 由非默认拨号器发起时，系统可能降级为拨号盘，且拿不到运营商结果。
     */
    private void executeDial(Task task) {
        try {
            Intent intent = new Intent(Intent.ACTION_CALL);
            intent.setData(Uri.parse("tel:" + task.code.replace("#", "%23")));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            // 拨号路径拿不到运营商回执，只能记成“已发起、待确认”
            finish(task, OUTCOME_UNKNOWN, "已通过拨号方式发起，结果以系统提示为准");
        } catch (Exception e) {
            finish(task, OUTCOME_FAIL, messageOf(e));
        }
    }

    /**
     * 一次执行的唯一收口：写状态、释放闸门、刷新通知并广播。
     *
     * 用 task 引用比对过滤迟到的回调，保证同一任务只结算一次。
     */
    private void finish(Task task, int outcome, String message) {
        if (task == null || currentTask != task) return;
        currentTask = null;

        if (timeoutTask != null) {
            handler.removeCallbacks(timeoutTask);
            timeoutTask = null;
        }

        if (outcome == OUTCOME_FAIL) {
            writeFailureState(message);
            statusText = "执行失败：" + message;
        } else {
            writeSuccessState(task, outcome, message);
            if (outcome == OUTCOME_UNKNOWN) {
                // 回执看不懂就用中性说法，别让用户以为一定生效了
                statusText = (task.isSet ? "已发起转移，待确认" : "已发起取消，待确认");
            } else {
                statusText = (task.isSet ? "转移成功" : "已取消转移")
                        + (message == null || message.isEmpty() ? "" : "：" + message);
            }
        }

        MmiExecutor.release();
        refreshNotification();
        notifyTransferDone(task, outcome);
        broadcastStateChanged();

        if (task.thenStop) {
            handler.postDelayed(this::stopSelf, TRANSIENT_STOP_DELAY_MS);
        }
    }

    // ===== 状态落盘 =====

    /**
     * 成功或待确认时写结果与转移状态。
     *
     * last_result 区分 ok / unknown：界面据此决定显示“已转移”还是“待确认”，
     * 两者都会更新转移目标，避免待确认时反复重发。
     */
    private void writeSuccessState(Task task, int outcome, String message) {
        SharedPreferences.Editor editor = AppPrefs.get(this).edit()
                .putString(AppPrefs.LAST_RESULT,
                        outcome == OUTCOME_OK ? AppPrefs.RESULT_OK : AppPrefs.RESULT_UNKNOWN)
                .putString(AppPrefs.LAST_ERROR, "")
                .putString(AppPrefs.LAST_RESPONSE, message == null ? "" : message)
                .putLong(AppPrefs.LAST_TIME, System.currentTimeMillis());

        if (task.isSet) {
            editor.putBoolean(AppPrefs.LAST_IS_SET, true)
                    .putBoolean(AppPrefs.IS_MANUAL, task.manual)
                    .putString(AppPrefs.LAST_PERSON, task.person == null ? "" : task.person)
                    .putString(AppPrefs.LAST_PHONE, task.phone == null ? "" : task.phone);
        } else {
            editor.putBoolean(AppPrefs.LAST_IS_SET, false)
                    .putBoolean(AppPrefs.IS_MANUAL, false)
                    .putString(AppPrefs.LAST_PERSON, "")
                    .putString(AppPrefs.LAST_PHONE, "");
        }
        editor.apply();
    }

    /** 失败时只覆盖结果与时间，保留上一次成功的转移状态。 */
    private void writeFailureState(String message) {
        AppPrefs.get(this).edit()
                .putString(AppPrefs.LAST_RESULT, AppPrefs.RESULT_FAIL)
                .putString(AppPrefs.LAST_ERROR, message == null ? "" : message)
                .putString(AppPrefs.LAST_RESPONSE, message == null ? "" : message)
                .putLong(AppPrefs.LAST_TIME, System.currentTimeMillis())
                .apply();
    }

    /** 通知界面刷新，广播只在本应用内可见。 */
    private void broadcastStateChanged() {
        Intent intent = new Intent(AppPrefs.ACTION_STATE_CHANGED);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    // ===== 解锁补偿 =====

    /** 注册解锁广播：常驻期间每次解锁都尝试补一次自动转移。 */
    private void registerUnlockReceiver() {
        if (unlockReceiver != null) return;
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                new Thread(() -> AutoSwitchRunner.runIfDue(getApplicationContext())).start();
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

    // ===== 通知 =====

    /** 创建低优先级状态通知渠道，不响铃不打扰。 */
    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_STATUS, "转移状态", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示当前值班人员与呼叫转移状态");
        channel.setShowBadge(false);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);

        ensureDoneChannel(manager);
    }

    /** 创建「转移成功提醒」渠道；重复调用无副作用。高优先级才能弹出横幅。 */
    private static void ensureDoneChannel(NotificationManager manager) {
        NotificationChannel done = new NotificationChannel(
                CHANNEL_DONE, "转移成功提醒", NotificationManager.IMPORTANCE_HIGH);
        done.setDescription("后台自动完成呼叫转移后弹出提醒，说明当前已转接到谁");
        done.setShowBadge(true);
        done.setVibrationPattern(new long[]{0, 120});
        manager.createNotificationChannel(done);
    }

    /**
     * 转移成功后弹一条横幅通知。
     *
     * 只提示「自动执行且判定成功」的情况：手动操作时用户正看着界面，
     * 再来一条通知纯属打扰；待确认或失败也不弹，避免误导。
     */
    private void notifyTransferDone(Task task, int outcome) {
        if (outcome != OUTCOME_OK || task == null || !task.isSet || task.manual) return;
        postDoneNotification(this, task.person);
    }

    /** 发一条「已转移到 X」的横幅通知；抽成静态便于单独验证。 */
    private static void postDoneNotification(Context ctx, String person) {
        NotificationManager manager = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        ensureDoneChannel(manager);
        Task task = new Task();
        task.person = person;

        String name = task.person == null || task.person.isEmpty()
                ? AppPrefs.cachedTodayPerson(ctx) : task.person;
        if (name == null || name.isEmpty()) name = "今日值班人";

        Intent openIntent = new Intent(ctx, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(ctx, 2, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String time = java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));

        Notification n = new NotificationCompat.Builder(ctx, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("已转移到 " + name)
                .setContentText("呼叫转移已生效 · " + time)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .build();
        // 用递增 id：每次成功都当成一条新通知，避免"同 id 只提醒一次"把横幅吃掉
        manager.notify(NOTIFICATION_ID_DONE + (int) (System.currentTimeMillis() / 1000 % 10000), n);
        // 横幅在部分 ROM 上不弹，用浮层兜底
        com.eafnv.callforward.util.TransferPopup.show(ctx, name);
    }

    /** 前台已启动时刷新通知内容。 */
    private void refreshNotification() {
        if (!foregroundStarted) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    /** 构建常驻通知；常驻代理形态下附带“停止自动转移”按钮。 */
    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(agentMode ? "值班助手 · 后台自动转移" : "值班助手")
                .setContentText(buildStatusLine())
                .setStyle(new NotificationCompat.BigTextStyle().bigText(buildStatusLine()))
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setShowWhen(false)
                .setOngoing(true);

        if (agentMode) {
            Intent stopIntent = new Intent(this, MmiService.class);
            stopIntent.setAction(MmiExecutor.ACTION_STOP_AGENT);
            PendingIntent stopPending = PendingIntent.getService(this, 1, stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(0, "停止自动转移", stopPending);
        }
        return builder.build();
    }

    /** 拼出通知正文：今日值班人 + 当前状态。 */
    private String buildStatusLine() {
        String today = AppPrefs.cachedTodayPerson(this);
        String state;
        if (statusText != null && !statusText.isEmpty()) {
            state = statusText;
        } else {
            SharedPreferences prefs = AppPrefs.get(this);
            String result = prefs.getString(AppPrefs.LAST_RESULT, "");
            boolean isSet = prefs.getBoolean(AppPrefs.LAST_IS_SET, false);
            String person = prefs.getString(AppPrefs.LAST_PERSON, "");
            String phone = prefs.getString(AppPrefs.LAST_PHONE, "");

            if (AppPrefs.RESULT_FAIL.equals(result)) {
                state = "上次操作失败";
            } else if (AppPrefs.RESULT_UNKNOWN.equals(result) && isSet) {
                state = "已发起转移，待确认";
            } else if (isSet) {
                state = person == null || person.isEmpty() ? "已转移至 " + phone : "已转移至 " + person;
            } else {
                state = "当前未设置转移";
            }
        }
        return today == null || today.isEmpty() ? state : "今日值班 " + today + " · " + state;
    }

    /** 退出前台并移除通知。 */
    private void stopForegroundCompat() {        if (!foregroundStarted) return;
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }
        foregroundStarted = false;
    }

    /** 取异常的可读信息，空消息时退回类名。 */
    private static String messageOf(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    /** 一次执行请求的快照，参数与来源都随 Intent 带过来。 */
    private static final class Task {
        String code;
        boolean isSet;
        String person;
        String phone;
        boolean manual;
        boolean auto;
        boolean thenStop;
    }
}
