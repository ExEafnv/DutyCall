package com.eafnv.callforward.data;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 一天的值班交接日志。
 *
 * 每天最多一条（epochDay 唯一），后续补充直接编辑内容，
 * 归档时只改 folderId，不改日志本身。
 */
@Entity(tableName = "duty_log", indices = {@Index(value = "epochDay", unique = true)})
public class DutyLog {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 日志所属日期，用 epochDay 存，避免时区换算。 */
    public long epochDay;

    /** 日志正文。 */
    public String content;

    /** 所属目录 id，0 表示未归档。 */
    public long folderId;

    public long createdAt;

    public long updatedAt;
}
