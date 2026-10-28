package com.eafnv.callforward.mmi;

import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MMI 指令的唯一出口，也是全局的单飞闸门。
 *
 * 前台点击、后台定时、解锁补偿都必须经由这里下发指令：先靠 CAS 抢占 IN_FLIGHT，
 * 抢不到就放弃本次请求，保证同一时刻只有一条指令在途，
 * 从根上杜绝“前台和后台同时发出两条不同转移指令”。
 */
public final class MmiExecutor {

    // ===== 广播动作 =====

    /** 执行一次转移或取消。 */
    public static final String ACTION_EXECUTE = "com.eafnv.callforward.action.EXECUTE";
    /** 拉起常驻代理（悬浮窗 + 常驻通知）。 */
    public static final String ACTION_START_AGENT = "com.eafnv.callforward.action.START_AGENT";
    /** 停止常驻代理。 */
    public static final String ACTION_STOP_AGENT = "com.eafnv.callforward.action.STOP_AGENT";
    /** 刷新常驻通知，不改变执行状态。 */
    public static final String ACTION_REFRESH = "com.eafnv.callforward.action.REFRESH";

    // ===== Intent 参数 =====

    // 指令码决定执行内容，其余参数只用于状态展示和来源标记
    public static final String EXTRA_CODE = "extra_code";
    public static final String EXTRA_IS_SET = "extra_is_set";
    public static final String EXTRA_PERSON = "extra_person";
    public static final String EXTRA_PHONE = "extra_phone";
    public static final String EXTRA_MANUAL = "extra_manual";
    public static final String EXTRA_AUTO = "extra_auto";

    // ===== 指令模板 =====

    /** 无条件转移模板，%s 为要转移到的号码。 */
    private static final String SET_TEMPLATE = "**21*%s#";

    /** 无条件转移：取消。 */
    public static final String CANCEL_CODE = "##21#";

    /** 单飞闸门：true 表示已有一条指令在途，其他请求必须让路。 */
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    private MmiExecutor() {
    }

    /** 用号码拼出无条件转移的 MMI 指令。 */
    public static String setCode(String phone) {
        return String.format(SET_TEMPLATE, phone);
    }

    /** 是否有指令正在执行。 */
    public static boolean isBusy() {
        return IN_FLIGHT.get();
    }

    /** 抢占执行权（CAS）；返回 false 表示已有指令在途，调用方应放弃本次请求。 */
    public static boolean acquire() {
        return IN_FLIGHT.compareAndSet(false, true);
    }

    /**
     * 归还执行权。
     *
     * 成功、失败、超时、服务销毁都必须走到这里，否则后续指令会被永久挡在门外。
     */
    public static void release() {
        IN_FLIGHT.set(false);
    }

    /**
     * 请求执行一次转移或取消。
     *
     * 真正的 USSD 交互在 {@link MmiService} 中完成，这里只负责把参数打包进 Intent。
     *
     * @param person 姓名（用于状态展示），取消时传 null
     * @param phone  号码（用于状态展示），取消时传 null
     * @param manual 是否手动指定号码
     * @param auto   是否由后台自动任务发起
     */
    public static void execute(Context ctx, String code, boolean isSet,
                               String person, String phone, boolean manual, boolean auto) {
        Intent intent = new Intent(ctx, MmiService.class);
        intent.setAction(ACTION_EXECUTE);
        intent.putExtra(EXTRA_CODE, code);
        intent.putExtra(EXTRA_IS_SET, isSet);
        intent.putExtra(EXTRA_PERSON, person);
        intent.putExtra(EXTRA_PHONE, phone);
        intent.putExtra(EXTRA_MANUAL, manual);
        intent.putExtra(EXTRA_AUTO, auto);
        start(ctx, intent);
    }

    /** 启动常驻代理（悬浮窗 + 常驻通知）。 */
    public static void startAgent(Context ctx) {
        Intent intent = new Intent(ctx, MmiService.class);
        intent.setAction(ACTION_START_AGENT);
        start(ctx, intent);
    }

    /** 停止常驻代理。 */
    public static void stopAgent(Context ctx) {
        Intent intent = new Intent(ctx, MmiService.class);
        intent.setAction(ACTION_STOP_AGENT);
        start(ctx, intent);
    }

    /** 刷新常驻通知（不改变执行状态）。 */
    public static void refresh(Context ctx) {
        Intent intent = new Intent(ctx, MmiService.class);
        intent.setAction(ACTION_REFRESH);
        start(ctx, intent);
    }

    /** 统一用 startForegroundService 拉起服务，规避后台启动限制。 */
    private static void start(Context ctx, Intent intent) {
        try {
            ContextCompat.startForegroundService(ctx.getApplicationContext(), intent);
        } catch (Exception e) {
            // 后台启动前台服务被系统拒绝（省电策略等）时静默失败，
            // 由用户下次在前台操作或下次解锁兜底。
        }
    }
}
