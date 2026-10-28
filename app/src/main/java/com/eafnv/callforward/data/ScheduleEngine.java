package com.eafnv.callforward.data;

import com.ezylang.evalex.EvaluationException;
import com.ezylang.evalex.Expression;
import com.ezylang.evalex.config.ExpressionConfiguration;
import com.ezylang.evalex.data.EvaluationValue;
import com.ezylang.evalex.functions.AbstractFunction;
import com.ezylang.evalex.functions.FunctionParameter;
import com.ezylang.evalex.parser.Token;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 排班解析引擎：把某一天的日期换算成具体的值班人。
 *
 * 计算全是纯函数，不读数据库，日历批量推算多天时可以直接调用。
 */
public class ScheduleEngine {

    // ===== 公式引擎的静态内部类与配置 =====

    /** 表达式里的 mod(a, b)，返回非负余数。 */
    @FunctionParameter(name = "a")
    @FunctionParameter(name = "b")
    static class ModFunction extends AbstractFunction {
        @Override
        public EvaluationValue evaluate(Expression expression, Token functionToken, EvaluationValue... params) {
            int a = params[0].getNumberValue().intValue();
            int b = params[1].getNumberValue().intValue();
            return EvaluationValue.numberValue(BigDecimal.valueOf(Math.floorMod(a, b)));
        }
    }

    /** 表达式里的 if(条件, 真值, 假值)，只求值命中的那个分支。 */
    @FunctionParameter(name = "condition")
    @FunctionParameter(name = "resultIfTrue", isLazy = true)
    @FunctionParameter(name = "resultIfFalse", isLazy = true)
    static class IfFunction extends AbstractFunction {
        @Override
        public EvaluationValue evaluate(Expression expression, Token functionToken, EvaluationValue... params) {
            try {
                boolean condition = params[0].getBooleanValue();
                if (condition) {
                    return expression.evaluateSubtree(params[1].getExpressionNode());
                } else {
                    return expression.evaluateSubtree(params[2].getExpressionNode());
                }
            } catch (EvaluationException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** 公式引擎配置，在默认语法上注册 mod 与 if 两个函数。 */
    private static final ExpressionConfiguration CUSTOM_CONFIG = ExpressionConfiguration.defaultConfiguration()
            .withAdditionalFunctions(
                    Map.entry("mod", new ModFunction()), Map.entry("MOD", new ModFunction()),
                    Map.entry("if", new IfFunction()), Map.entry("IF", new IfFunction())
            );

    // ===== 对外解析入口 =====

    /**
     * 解析某一天的值班人：单日换班优先，没有覆盖再按排班规则算。
     *
     * @return 当天值班人，无人员可排时返回 null
     */
    public static Person resolve(List<Person> people, ScheduleVersion version, DayOverride override, LocalDate date,
                                 int offsetM, int offsetW, int offsetD) {
        if (people == null || people.isEmpty()) return null;

        if (override != null) {
            for (Person p : people) {
                if (p.id == override.personId) return p;
            }
        }
        return computeByRule(people, version, date, offsetM, offsetW, offsetD);
    }

    /** 按版本里的模式分派到对应的算法，下标越界时回落到第一个人。 */
    public static Person computeByRule(List<Person> people, ScheduleVersion version, LocalDate date,
                                       int offsetM, int offsetW, int offsetD) {
        if (people == null || people.isEmpty()) return null;
        if (version == null) return null;

        int index;
        switch (version.mode) {
            case ScheduleMode.CUSTOM:
                index = computeCustomIndex(people, version, date, offsetM, offsetW, offsetD);
                break;
            case ScheduleMode.WEEKLY:
            default:
                index = computeWeeklyIndex(people, version, date);
                break;
        }
        if (index < 0 || index >= people.size()) index = 0;
        return people.get(index);
    }

    // ===== 模式 4：自定义公式 =====
    /**
     * 模式 4：用自定义公式算出下标。
     *
     * 偏移量已经加进 d、w、m，公式里拿到的就是偏移后的值。
     *
     * @return 人数范围内的下标，公式出错时退回 0
     */
    static int computeCustomIndex(List<Person> people, ScheduleVersion v, LocalDate date,
                                  int offsetM, int offsetW, int offsetD) {
        if (v.extraParams == null || v.extraParams.trim().isEmpty()) return 0;
        int N = people.size();
        LocalDate anchor = LocalDate.ofEpochDay(v.anchorMondayEpochDay);

        // 应用偏移量。
        long d = ChronoUnit.DAYS.between(anchor, date) + offsetD;
        long w = ChronoUnit.WEEKS.between(anchor.with(DayOfWeek.MONDAY), date.with(DayOfWeek.MONDAY)) + offsetW;
        long m = ChronoUnit.MONTHS.between(anchor.withDayOfMonth(1), date.withDayOfMonth(1)) + offsetM;
        int dow = date.getDayOfWeek().getValue();
        int dom = date.getDayOfMonth();

        try {
            Expression expr = new Expression(v.extraParams, CUSTOM_CONFIG);

            expr.with("N", N).with("n", N)
                    .with("d", d).with("D", d)
                    .with("w", w).with("W", w)
                    .with("m", m).with("M", m)
                    .with("dow", dow).with("DOW", dow)
                    .with("dom", dom).with("DOM", dom);

            EvaluationValue result = expr.evaluate();
            int val = result.getNumberValue().intValue();

            return Math.floorMod(val - 1, N);
        } catch (Exception e) {
            // 公式写错时退回第一个人，避免整个日历都算不出来。
            e.printStackTrace();
            return 0;
        }
    }

    // ===== 公式校验 =====
    /**
     * 校验表达式在给定人数下能否正常求值。
     *
     * @param formula 待校验的表达式
     * @param N 参与排班的人数
     * @return 校验通过返回 null，否则返回错误提示信息
     */
    public static String validateFormula(String formula, int N) {
        if (formula == null || formula.trim().isEmpty()) {
            return "公式不能为空";
        }
        try {
            Expression expr = new Expression(formula, CUSTOM_CONFIG);
            expr.with("N", N).with("n", N)
                    .with("d", 1).with("D", 1)
                    .with("w", 1).with("W", 1)
                    .with("m", 1).with("M", 1)
                    .with("dow", 1).with("DOW", 1)
                    .with("dom", 1).with("DOM", 1);

            expr.evaluate();
            return null;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null) msg = e.getClass().getSimpleName();
            return msg;
        }
    }

    // ===== 模式 1：通用每周轮转 =====
    /**
     * 模式 1：通用轮转，按锚点周与锚点人推算下标。
     *
     * @return 人数范围内的下标
     */
    static int computeWeeklyIndex(List<Person> people, ScheduleVersion v, LocalDate date) {
        int N = people.size();
        int anchorIdx = indexOf(people, v.anchorPersonId);
        if (anchorIdx < 0) anchorIdx = 0;

        LocalDate anchorMonday = LocalDate.ofEpochDay(v.anchorMondayEpochDay);
        LocalDate targetMonday = date.with(DayOfWeek.MONDAY);
        long weeks = ChronoUnit.WEEKS.between(anchorMonday, targetMonday);
        int t = date.getDayOfWeek().getValue() - 1;

        return Math.floorMod((int) (anchorIdx + weeks + t), N);
    }

    /** 按 id 找人员在列表中的下标，找不到返回 -1。 */
    public static int indexOf(List<Person> people, long personId) {
        for (int i = 0; i < people.size(); i++) {
            if (people.get(i).id == personId) return i;
        }
        return -1;
    }
}