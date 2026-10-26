package com.eafnv.callforward.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "schedule_version")
public class ScheduleVersion {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public int mode;                    // ScheduleMode 常量
    public long effectiveFromEpochDay;  // 从这天起生效
    public long anchorMondayEpochDay;   // 锚点周的周一
    public long anchorPersonId;         // 锚点周一那天是谁
    public String extraParams;          // JSON，分组大小/分段定义
    public long createdAt;

    public ScheduleVersion() {}
}