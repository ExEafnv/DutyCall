package com.eafnv.callforward.mmi;

import android.content.Context;

import com.eafnv.callforward.data.AppDatabase;
import com.eafnv.callforward.data.DayOverride;
import com.eafnv.callforward.data.Person;
import com.eafnv.callforward.data.ScheduleEngine;
import com.eafnv.callforward.data.ScheduleVersion;
import com.eafnv.callforward.util.AppPrefs;

import java.time.LocalDate;
import java.util.List;

/**
 * 临时换人后的「当天值班人」解析。
 *
 * 值班中途临时换人时，如果还要用户再点一次「一键切换」，很容易忘——
 * 结果电话仍旧转给上一个人。界面在写库之后用这里的结果立刻补一次转移。
 *
 * 只负责「解析」：是否真的有权限、执行器忙不忙，交给调用方复用原有的转移入口，
 * 这样权限申请、忙碌提示等逻辑都只有一份。
 *
 * 要读数据库，<b>必须在后台线程调用</b>。
 */
public final class TodayOverrideSync {

    private TodayOverrideSync() {
    }

    /**
     * 如果改动的是今天，返回今天应当值班的人；否则返回 null。
     *
     * 返回值同时覆盖「临时换人」和「清除临时换人（恢复规则）」两种情况：
     * 清除之后按规则算出来的当班人可能变了，同样需要补一次转移。
     *
     * @param context     任意 context
     * @param changedDate 被修改的那一天
     * @return 需要转移的目标；改的不是今天、当天没人、或号码为空时返回 null
     */
    public static Person personToTransfer(Context context, LocalDate changedDate) {
        if (context == null || changedDate == null) return null;
        final LocalDate today = LocalDate.now();
        if (!today.equals(changedDate)) return null;

        Context app = context.getApplicationContext();
        AppDatabase db = AppDatabase.get(app);
        List<Person> people = db.personDao().getAll();
        if (people == null || people.isEmpty()) return null;

        List<ScheduleVersion> versions = db.scheduleDao().getAllVersions();
        DayOverride override = db.scheduleDao().findOverride(today.toEpochDay());
        Person person = ScheduleEngine.resolve(people,
                pickVersion(versions, today.toEpochDay()), override, today,
                AppPrefs.offsetM(app), AppPrefs.offsetW(app), AppPrefs.offsetD(app));
        if (person == null || person.phoneNumber == null || person.phoneNumber.isEmpty()) {
            return null;
        }
        return person;
    }

    /** 从按生效日倒序的版本列表里挑出当天生效的那一版。 */
    private static ScheduleVersion pickVersion(List<ScheduleVersion> versions, long day) {
        if (versions == null) return null;
        for (ScheduleVersion v : versions) {
            if (v.effectiveFromEpochDay <= day) return v;
        }
        return null;
    }
}
