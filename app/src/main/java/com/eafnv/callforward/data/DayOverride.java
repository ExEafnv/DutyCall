package com.eafnv.callforward.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 单日换班覆盖：指定某一天的值班人，优先级高于排班规则。
 */
@Entity(tableName = "day_override")
public class DayOverride {
    /** 日期，按 LocalDate.toEpochDay 存储，一天最多一条。 */
    @PrimaryKey
    public long epochDay;

    /** 当天值班人的 Person.id。 */
    public long personId;
}