package com.eafnv.callforward.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * 公式库的 Room 数据访问接口。
 */
@Dao
public interface FormulaDao {

    /** 取出全部公式，最近创建的排在前面。 */
    @Query("SELECT * FROM formula ORDER BY createdAt DESC, id DESC")
    List<Formula> getAll();

    /** 按 id 查询单条公式，不存在时返回 null。 */
    @Query("SELECT * FROM formula WHERE id = :id")
    Formula getById(long id);

    /** 公式总条数。 */
    @Query("SELECT COUNT(*) FROM formula")
    int count();

    /**
     * 新增一条公式。
     *
     * @return 新记录的自增 id
     */
    @Insert
    long insert(Formula formula);

    /** 整条覆盖更新，按 id 匹配。 */
    @Update
    void update(Formula formula);

    /** 按 id 删除。 */
    @Delete
    void delete(Formula formula);
}
