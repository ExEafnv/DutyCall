package com.eafnv.callforward;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.eafnv.callforward.data.DayOverride;
import com.eafnv.callforward.data.Person;
import com.eafnv.callforward.data.ScheduleEngine;
import com.eafnv.callforward.data.ScheduleMode;
import com.eafnv.callforward.data.ScheduleVersion;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class ScheduleEngineTest {

    // 辅助方法：快速创建 N 个人 (A, B, C...)
    private List<Person> makePeople(int n) {
        List<Person> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Person p = new Person();
            p.id = i + 1;
            p.name = String.valueOf((char) ('A' + i));
            p.phoneNumber = "1380000000" + i;
            list.add(p);
        }
        return list;
    }

    // 辅助方法：验证某一天的排班人员名字（默认无偏移量）
    private void assertName(List<Person> people, ScheduleVersion v, String date, String expected) {
        assertName(people, v, date, expected, 0, 0, 0);
    }

    // 辅助方法：带偏移量的验证
    private void assertName(List<Person> people, ScheduleVersion v, String date, String expected,
                            int offsetM, int offsetW, int offsetD) {
        Person p = ScheduleEngine.computeByRule(people, v, LocalDate.parse(date), offsetM, offsetW, offsetD);
        if (expected == null) {
            assertNull("日期 " + date + " 应该无排班", p);
        } else {
            assertEquals("日期 " + date + " 排班错误", expected, p.name);
        }
    }

    // =====================================================================
    // 1. 基础回归测试：确保原有逻辑没被破坏
    // =====================================================================
    @Test
    public void test_Override_优先级最高() {
        List<Person> people = makePeople(3); // A, B, C
        ScheduleVersion v = new ScheduleVersion();
        v.mode = ScheduleMode.WEEKLY;
        v.anchorMondayEpochDay = LocalDate.of(2026, 10, 26).toEpochDay();
        v.anchorPersonId = 1; // A

        LocalDate testDate = LocalDate.parse("2026-10-28"); // 周三，按规则应该是 C
        DayOverride override = new DayOverride();
        override.epochDay = testDate.toEpochDay();
        override.personId = 2; // B

        // 使用带偏移量的 resolve 方法（全传 0）
        Person p = ScheduleEngine.resolve(people, v, override, testDate, 0, 0, 0);
        assertEquals("单日覆盖失效，应强制为 B", "B", p.name);
    }

    @Test
    public void test_Weekly_7人完美循环() {
        List<Person> people = makePeople(7);
        ScheduleVersion v = new ScheduleVersion();
        v.mode = ScheduleMode.WEEKLY;
        v.anchorMondayEpochDay = LocalDate.of(2026, 10, 26).toEpochDay();
        v.anchorPersonId = 1; // A

        assertName(people, v, "2026-10-26", "A");
        assertName(people, v, "2026-11-01", "G");
        assertName(people, v, "2026-11-02", "B");
    }

    // =====================================================================
    // 2. 偏移量微调测试
    // =====================================================================

    @Test
    public void test_Offset_日偏移微调() {
        List<Person> people = makePeople(5);
        ScheduleVersion v = new ScheduleVersion();
        v.mode = ScheduleMode.CUSTOM;
        v.anchorMondayEpochDay = LocalDate.of(2026, 10, 26).toEpochDay();
        v.extraParams = "mod(d, N) + 1"; // 基础公式：每天轮换

        // 无偏移：10月26日(d=0) 是 A
        assertName(people, v, "2026-10-26", "A", 0, 0, 0);

        // 日偏移 +1：相当于快进1天，10月26日按 10月27日 算，应该是 B
        assertName(people, v, "2026-10-26", "B", 0, 0, 1);

        // 日偏移 -1：相当于倒退1天，10月26日按 10月25日 算，应该是 E
        assertName(people, v, "2026-10-26", "E", 0, 0, -1);
    }

    @Test
    public void test_Offset_周偏移微调() {
        List<Person> people = makePeople(5);
        ScheduleVersion v = new ScheduleVersion();
        v.mode = ScheduleMode.CUSTOM;
        v.anchorMondayEpochDay = LocalDate.of(2026, 10, 26).toEpochDay();
        v.extraParams = "mod(w, N) + 1"; // 基础公式：按周轮换

        // 无偏移：第0周是 A
        assertName(people, v, "2026-10-26", "A", 0, 0, 0);

        // 周偏移 +2：相当于快进2周，应该是 C
        assertName(people, v, "2026-10-26", "C", 0, 2, 0);
    }

    // =====================================================================
    // 3. 复杂公式与偏移结合测试 (模拟实际班组的痛点)
    // =====================================================================
    @Test
    public void test_Offset_复杂公式_月偏移快进() {
        List<Person> people = makePeople(7);
        ScheduleVersion v = new ScheduleVersion();
        v.mode = ScheduleMode.CUSTOM;
        v.anchorMondayEpochDay = LocalDate.of(2026, 10, 26).toEpochDay();

        // 复杂的 7 人实战公式
        v.extraParams = "if(dow>=6, mod(w,N)+1, if(dow==mod(m,6), 6, dow))";

        // 无偏移状态下，10月26日（第0个月 m=0），周一不替换，是 A
        assertName(people, v, "2026-10-26", "A", 0, 0, 0);

        // 月偏移 +1：相当于快进到第 1 个月，触发 M6 替换周一的逻辑，所以 10月26日 变成了 F
        assertName(people, v, "2026-10-26", "F", 1, 0, 0);
    }
}