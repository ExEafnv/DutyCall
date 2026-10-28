package com.eafnv.callforward.util;

import java.time.LocalDate;
import java.util.Locale;

/**
 * 日志的搜索与日期文案工具，主列表与归档视图共用。
 */
public final class LogSearch {

    private LogSearch() {
    }

    /**
     * 判断一条日志是否命中搜索词。
     *
     * 支持内容关键词、值班人姓名，以及日期的多种写法
     * （2026-10-27 / 10-27 / 10月27日 / 27日）。
     */
    public static boolean matches(String content, String personName, long epochDay, String needle) {
        if (needle == null || needle.isEmpty()) return true;

        String text = content == null ? "" : content.toLowerCase(Locale.getDefault());
        if (text.contains(needle)) return true;

        if (personName != null
                && personName.toLowerCase(Locale.getDefault()).contains(needle)) {
            return true;
        }

        LocalDate date = LocalDate.ofEpochDay(epochDay);
        String yearDay = date.toString();
        String monthDay = String.format(Locale.getDefault(), "%02d-%02d",
                date.getMonthValue(), date.getDayOfMonth());
        String cnMonthDay = date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
        String cnFull = date.getYear() + "年" + cnMonthDay;
        String dayOnly = date.getDayOfMonth() + "日";

        return yearDay.contains(needle)
                || monthDay.contains(needle)
                || cnMonthDay.contains(needle)
                || cnFull.contains(needle)
                || dayOnly.contains(needle);
    }

    /** 把日期格式化成「10月27日 星期二」。 */
    public static String describeDay(long epochDay) {
        LocalDate date = LocalDate.ofEpochDay(epochDay);
        String[] names = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
        return date.getMonthValue() + "月" + date.getDayOfMonth() + "日 "
                + names[date.getDayOfWeek().getValue() - 1];
    }
}
