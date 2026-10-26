package com.eafnv.callforward.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ScheduleDao {

    @Query("SELECT * FROM schedule_version ORDER BY effectiveFromEpochDay DESC")
    List<ScheduleVersion> getAllVersions();

    @Query("SELECT * FROM schedule_version WHERE effectiveFromEpochDay <= :day " +
            "ORDER BY effectiveFromEpochDay DESC LIMIT 1")
    ScheduleVersion findVersionForDay(long day);

    @Insert
    long insertVersion(ScheduleVersion v);

    @Query("SELECT * FROM day_override WHERE epochDay = :day LIMIT 1")
    DayOverride findOverride(long day);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertOverride(DayOverride o);

    @Query("DELETE FROM day_override WHERE epochDay = :day")
    void deleteOverride(long day);

    @Query("DELETE FROM schedule_version")
    void deleteAllVersions();

    @Query("DELETE FROM day_override")
    void deleteAllOverrides();
}