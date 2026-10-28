package com.eafnv.callforward.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * 人员的 Room 数据访问接口。
 */
@Dao
public interface PersonDao {
    /** 取出全部人员，按显示顺序排序。 */
    @Query("SELECT * FROM person ORDER BY sortOrder ASC, id ASC")
    List<Person> getAll();

    /** 按 id 查询单条人员，不存在时返回 null。 */
    @Query("SELECT * FROM person WHERE id = :id")
    Person getById(long id);

    /** 人员总条数。 */
    @Query("SELECT COUNT(*) FROM person")
    int count();

    /** 当前最大排序值，空表时返回 null。 */
    @Query("SELECT MAX(sortOrder) FROM person")
    Integer maxSortOrder();

    /**
     * 新增一名人员。
     *
     * @return 新记录的自增 id
     */
    @Insert
    long insert(Person person);

    /** 整条覆盖更新，按 id 匹配。 */
    @Update
    void update(Person person);

    /** 按 id 删除。 */
    @Delete
    void delete(Person person);
}