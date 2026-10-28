package com.eafnv.callforward.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * 排班版本与单日换班的 Room 数据访问接口。
 */
@Dao
public interface ScheduleDao {

    /** 全部排班版本，生效日最晚的排在最前。 */
    @Query("SELECT * FROM schedule_version ORDER BY effectiveFromEpochDay DESC, id DESC")
    List<ScheduleVersion> getAllVersions();

    /**
     * 查找该天生效的排班版本。
     *
     * @param day 目标日期，按 epochDay 传入
     * @return 生效日不晚于该天的最新版本，没有则返回 null
     */
    @Query("SELECT * FROM schedule_version WHERE effectiveFromEpochDay <= :day " +
            "ORDER BY effectiveFromEpochDay DESC, id DESC LIMIT 1")
    ScheduleVersion findVersionForDay(long day);

    /**
     * 新增一个排班版本。
     *
     * @return 新记录的自增 id
     */
    @Insert
    long insertVersion(ScheduleVersion v);

    /** 查询某天的单日换班，没有则返回 null。 */
    @Query("SELECT * FROM day_override WHERE epochDay = :day LIMIT 1")
    DayOverride findOverride(long day);

    /** 一次取出某个日期区间内的单日换班，供整月日历使用。 */
    @Query("SELECT * FROM day_override WHERE epochDay BETWEEN :from AND :to")
    List<DayOverride> getOverridesBetween(long from, long to);

    /** 写入某天的单日换班，已存在则整条覆盖。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertOverride(DayOverride o);

    /** 清除某天的单日换班。 */
    @Query("DELETE FROM day_override WHERE epochDay = :day")
    void deleteOverride(long day);

    /** 清空所有排班版本，重建排班时使用。 */
    @Query("DELETE FROM schedule_version")
    void deleteAllVersions();

    /** 整条覆盖更新排班版本，按 id 匹配。 */
    @Update
    void updateVersion(ScheduleVersion version);

    /** 清空所有单日换班。 */
    @Query("DELETE FROM day_override")
    void deleteAllOverrides();
}