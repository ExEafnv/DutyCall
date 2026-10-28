package com.eafnv.callforward.mmi;

import android.content.Context;
import android.content.SharedPreferences;

import com.eafnv.callforward.data.AppDatabase;
import com.eafnv.callforward.data.DayOverride;
import com.eafnv.callforward.data.Person;
import com.eafnv.callforward.data.ScheduleEngine;
import com.eafnv.callforward.data.ScheduleVersion;
import com.eafnv.callforward.util.AppPrefs;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 每日自动转移的判定与发起。
 *
 * 两个入口共用：精确定时闹钟（到点触发）与解锁补偿（错过后补一次）。
 * 关键在 last_auto_day：条件满足后先把“今天已执行”落盘，再去做查库、下发，
 * 这样闹钟与解锁补偿即使同时进入，也只有一个能继续往下走。
 */
public final class AutoSwitchRunner {

    private AutoSwitchRunner() {
    }

    /**
     * 若今天该执行且尚未执行，则发起一次自动转移。
     *
     * 必须在工作线程调用（会访问数据库）。
     */
    public static void runIfDue(Context context) {
        Context app = context.getApplicationContext();
        if (!AppPrefs.autoEnabled(app)) return;

        LocalDate today = LocalDate.now();
        long epochDay = today.toEpochDay();

        // 今天已自动执行过；先判日期，避免后面的查库白做
        SharedPreferences prefs = AppPrefs.get(app);
        if (prefs.getLong(AppPrefs.LAST_AUTO_DAY, Long.MIN_VALUE) == epochDay) return;

        LocalTime now = LocalTime.now();
        LocalTime target = LocalTime.of(AppPrefs.autoHour(app), AppPrefs.autoMinute(app));
        if (now.isBefore(target)) return;

        // 先占位，避免闹钟与解锁补偿并发导致重复下发
        prefs.edit().putLong(AppPrefs.LAST_AUTO_DAY, epochDay).apply();

        Person person = resolveToday(app, today);
        if (person == null) {
            fail(app, "今日没有排班人员");
            return;
        }
        // 缓存给通知栏用，Service 不必再查库
        AppPrefs.cacheToday(app, person.name, person.phoneNumber);

        if (AppPrefs.isDialMode(app)) {
            // 兼容模式拿不到运营商结果，不能用于无人值守的自动执行
            fail(app, "兼容模式不支持后台自动执行");
            return;
        }

        MmiExecutor.execute(app, MmiExecutor.setCode(person.phoneNumber), true,
                person.name, person.phoneNumber, false, true);
    }

    // ===== 排班解析 =====

    /** 解析今日值班人员；与首页展示使用同一套引擎与偏移量。 */
    public static Person resolveToday(Context context, LocalDate date) {
        AppDatabase db = AppDatabase.get(context);
        long day = date.toEpochDay();
        List<Person> people = db.personDao().getAll();
        if (people == null || people.isEmpty()) return null;

        // 注意：不能因为 schedule_version 为空就直接返回 null。
        // 当天可能存在“临时换班”的 DayOverride，ScheduleEngine 会优先用它。
        ScheduleVersion version = db.scheduleDao().findVersionForDay(day);
        DayOverride override = db.scheduleDao().findOverride(day);
        return ScheduleEngine.resolve(people, version, override, date,
                AppPrefs.offsetM(context), AppPrefs.offsetW(context), AppPrefs.offsetD(context));
    }

    /** 记录失败结果：界面读 last_result / last_error，并刷新通知。 */
    private static void fail(Context app, String message) {
        AppPrefs.get(app).edit()
                .putString(AppPrefs.LAST_RESULT, AppPrefs.RESULT_FAIL)
                .putString(AppPrefs.LAST_ERROR, message)
                .apply();
        MmiExecutor.refresh(app);
    }
}
