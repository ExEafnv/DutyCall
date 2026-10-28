package com.eafnv.callforward.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 公式库：一条可复用的自定义排班公式。
 */
@Entity(tableName = "formula")
public class Formula {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 备注名，用于在列表和排班页展示。 */
    public String name;

    /** 表达式本体，例如 mod(d, N) + 1。 */
    public String expression;

    /** 创建时间，毫秒时间戳，用于列表排序。 */
    public long createdAt;

    /** Room 实例化用的空构造。 */
    public Formula() {
    }

    /** 新建一条公式，id 由 Room 自动生成。 */
    public Formula(String name, String expression, long createdAt) {
        this.name = name;
        this.expression = expression;
        this.createdAt = createdAt;
    }
}
