package com.eafnv.callforward.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 日志目录，用于把一批日志归档。
 *
 * 删除目录时不级联删除日志，由 DAO 把目录内的日志退回"未归档"。
 */
@Entity(tableName = "log_folder")
public class LogFolder {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public String name;

    public long createdAt;
}
