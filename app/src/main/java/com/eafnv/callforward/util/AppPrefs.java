package com.eafnv.callforward.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;

/**
 * 全局配置与状态存储。
 *
 * 所有模块（Activity / Service / Receiver）都从这里读写，避免各自拼装字段名；
 * 同一份文件既存用户偏好，也存最近一次执行结果和今日排班缓存。
 */
public final class AppPrefs {

    /** 偏好文件名。 */
    public static final String FILE = "duty_prefs";

    // ===== 转移方式 =====

    /** 转移方式标识，取值为 {@link #MODE_SILENT} 或 {@link #MODE_DIAL}。 */
    public static final String MODE = "register_mode";
    /** 静默模式：应用内直接发 USSD，不拉起拨号盘。 */
    public static final String MODE_SILENT = "silent";
    /** 兼容模式：拉起系统拨号盘执行。 */
    public static final String MODE_DIAL = "dial";

    // ===== 后台自动转移 =====

    /** 是否开启每日自动转移，同时决定常驻代理是否存活。 */
    public static final String AUTO_ENABLED = "auto_switch_enabled";

    /** 转移成功后是否弹悬浮窗卡片。 */
    public static final String POPUP_ENABLED = "popup_enabled";
    /** 每日自动转移的小时（24 小时制）。 */
    public static final String AUTO_HOUR = "auto_switch_hour";
    /** 每日自动转移的分钟。 */
    public static final String AUTO_MINUTE = "auto_switch_minute";
    /** 自动转移默认小时。 */
    public static final int DEFAULT_HOUR = 8;
    /** 自动转移默认分钟。 */
    public static final int DEFAULT_MINUTE = 0;

    // ===== 当前转移状态 =====

    /** 当前是否处于已转移状态。 */
    public static final String LAST_IS_SET = "last_is_set";
    /** 当前被转移到的值班人姓名。 */
    public static final String LAST_PERSON = "last_person";
    /** 当前被转移到的号码。 */
    public static final String LAST_PHONE = "last_phone";
    /** 当前转移是否由用户手动指定号码。 */
    public static final String IS_MANUAL = "is_manual";
    /** 最近一次执行的时间戳（毫秒）。 */
    public static final String LAST_TIME = "last_time";

    // ===== 上一次执行结果 =====

    /** 执行结果：运营商明确回执成功。 */
    public static final String RESULT_OK = "ok";
    /** 执行结果：确认失败。 */
    public static final String RESULT_FAIL = "fail";
    /** 执行结果：指令已发出，但回执无法判定成败（不认识的文案，或拨号模式）。 */
    public static final String RESULT_UNKNOWN = "unknown";

    /** 执行结果，取值为 {@link #RESULT_OK}、{@link #RESULT_FAIL} 或 {@link #RESULT_UNKNOWN}。 */
    public static final String LAST_RESULT = "last_result";
    /** 失败原因或运营商返回的提示。 */
    public static final String LAST_ERROR = "last_error";
    /** 运营商回执原文，仅供排查，界面上不直接展示。 */
    public static final String LAST_RESPONSE = "last_response";
    /** 已自动执行过的 epochDay，用来保证同一天只自动执行一次。 */
    public static final String LAST_AUTO_DAY = "last_auto_day";

    // ===== 今日排班缓存 =====

    /** 今日值班人姓名缓存，供通知栏直接读取，避免 Service 查库。 */
    public static final String TODAY_PERSON = "today_person";
    /** 今日值班人号码缓存。 */
    public static final String TODAY_PHONE = "today_phone";
    /** 缓存所属的 epochDay，与今天不一致即视为过期。 */
    public static final String TODAY_DAY = "today_day";

    // ===== 排班进度微调 =====

    /** 月偏移量，用于微调轮转起点。 */
    public static final String OFFSET_M = "offset_m";
    /** 周偏移量，用于微调轮转起点。 */
    public static final String OFFSET_W = "offset_w";
    /** 日偏移量，用于微调轮转起点。 */
    public static final String OFFSET_D = "offset_d";

    // ===== 悬浮窗位置 =====

    /** 悬浮图标的横向坐标。 */
    public static final String OVERLAY_X = "overlay_x";
    /** 悬浮图标的纵向坐标。 */
    public static final String OVERLAY_Y = "overlay_y";

    // ===== 广播动作 =====

    /** 执行状态变化广播，仅应用内可见。 */
    public static final String ACTION_STATE_CHANGED = "com.eafnv.callforward.STATE_CHANGED";

    private AppPrefs() {
    }

    /** 全局偏好的统一入口，内部固定用 ApplicationContext。 */
    public static SharedPreferences get(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ===== 偏好读写：转移方式 =====

    /** 当前转移方式，未设置时默认静默模式。 */
    public static String mode(Context ctx) {
        return get(ctx).getString(MODE, MODE_SILENT);
    }

    /** 是否为兼容（拨号）模式。 */
    public static boolean isDialMode(Context ctx) {
        return MODE_DIAL.equals(mode(ctx));
    }

    /** 写入转移方式。 */
    public static void setMode(Context ctx, String value) {
        get(ctx).edit().putString(MODE, value).apply();
    }

    // ===== 偏好读写：自动转移 =====

    /** 是否开启了每日自动转移。 */
    public static boolean autoEnabled(Context ctx) {
        return get(ctx).getBoolean(AUTO_ENABLED, false);
    }

    /** 开启或关闭每日自动转移。 */
    public static void setAutoEnabled(Context ctx, boolean enabled) {
        get(ctx).edit().putBoolean(AUTO_ENABLED, enabled).apply();
    }

    /** 转移成功后是否弹悬浮窗卡片；关闭则只用系统通知。 */
    public static boolean popupEnabled(Context ctx) {
        return get(ctx).getBoolean(POPUP_ENABLED, true);
    }

    /** 开启或关闭成功提示的悬浮窗卡片。 */
    public static void setPopupEnabled(Context ctx, boolean enabled) {
        get(ctx).edit().putBoolean(POPUP_ENABLED, enabled).apply();
    }

    /** 自动转移的小时，未设置时用默认值。 */
    public static int autoHour(Context ctx) {
        return get(ctx).getInt(AUTO_HOUR, DEFAULT_HOUR);
    }

    /** 自动转移的分钟，未设置时用默认值。 */
    public static int autoMinute(Context ctx) {
        return get(ctx).getInt(AUTO_MINUTE, DEFAULT_MINUTE);
    }

    /** 同时写入自动转移的时分。 */
    public static void setAutoTime(Context ctx, int hour, int minute) {
        get(ctx).edit().putInt(AUTO_HOUR, hour).putInt(AUTO_MINUTE, minute).apply();
    }

    // ===== 偏好读写：状态与偏移量 =====

    /** 当前是否已设置转移。 */
    public static boolean lastIsSet(Context ctx) {
        return get(ctx).getBoolean(LAST_IS_SET, false);
    }

    /** 月偏移量，用于微调轮转起点。 */
    public static int offsetM(Context ctx) {
        return get(ctx).getInt(OFFSET_M, 0);
    }

    /** 周偏移量，用于微调轮转起点。 */
    public static int offsetW(Context ctx) {
        return get(ctx).getInt(OFFSET_W, 0);
    }

    /** 日偏移量，用于微调轮转起点。 */
    public static int offsetD(Context ctx) {
        return get(ctx).getInt(OFFSET_D, 0);
    }

    /** 一次性写入月、周、日三个偏移量。 */
    public static void setOffsets(Context ctx, int m, int w, int d) {
        get(ctx).edit()
                .putInt(OFFSET_M, m)
                .putInt(OFFSET_W, w)
                .putInt(OFFSET_D, d)
                .apply();
    }

    // ===== 偏好读写：今日缓存 =====

    /** 缓存今日值班人与号码，并记下缓存日期。 */
    public static void cacheToday(Context ctx, String person, String phone) {
        get(ctx).edit()
                .putString(TODAY_PERSON, person == null ? "" : person)
                .putString(TODAY_PHONE, phone == null ? "" : phone)
                .putLong(TODAY_DAY, LocalDate.now().toEpochDay())
                .apply();
    }

    /** 返回今日值班人姓名；若非今日缓存则返回空串。 */
    public static String cachedTodayPerson(Context ctx) {
        SharedPreferences p = get(ctx);
        if (p.getLong(TODAY_DAY, Long.MIN_VALUE) != LocalDate.now().toEpochDay()) return "";
        return p.getString(TODAY_PERSON, "");
    }

    /** 清空今日排班缓存。 */
    public static void clearTodayCache(Context ctx) {
        get(ctx).edit().remove(TODAY_PERSON).remove(TODAY_PHONE).remove(TODAY_DAY).apply();
    }
}
