package com.eafnv.callforward.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "day_override")
public class DayOverride {
    @PrimaryKey
    public long epochDay;   // 一个日期覆盖一条

    public long personId;
}