package com.eafnv.callforward.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * 交接日志与日志目录的读写。
 */
@Dao
public interface LogDao {

    // ===== 日志 =====

    /** 某一天的日志，没有则返回 null。 */
    @Query("SELECT * FROM duty_log WHERE epochDay = :day LIMIT 1")
    DutyLog getByDay(long day);

    /** 全部日志，新的在前。 */
    @Query("SELECT * FROM duty_log ORDER BY epochDay DESC")
    List<DutyLog> getAll();

    /** 某个目录下的日志。 */
    @Query("SELECT * FROM duty_log WHERE folderId = :folderId ORDER BY epochDay DESC")
    List<DutyLog> getByFolder(long folderId);

    /** 未归档的日志。 */
    @Query("SELECT * FROM duty_log WHERE folderId = 0 ORDER BY epochDay DESC")
    List<DutyLog> getUnfiled();

    /** 有日志的所有日期，供日历染色。 */
    @Query("SELECT epochDay FROM duty_log")
    List<Long> getAllDays();

    /** 按日期范围取日志，供日历当前月使用。 */
    @Query("SELECT epochDay FROM duty_log WHERE epochDay BETWEEN :from AND :to")
    List<Long> getDaysBetween(long from, long to);

    /** 新增日志；同一天重复写入时覆盖。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(DutyLog log);

    /** 更新日志内容。 */
    @Query("UPDATE duty_log SET content = :content, updatedAt = :updatedAt WHERE epochDay = :day")
    void updateByDay(long day, String content, long updatedAt);

    /** 按日期删除。 */
    @Query("DELETE FROM duty_log WHERE epochDay = :day")
    void deleteByDay(long day);

    /** 批量删除。 */
    @Query("DELETE FROM duty_log WHERE epochDay IN (:days)")
    void deleteByDays(List<Long> days);

    /** 批量归档到指定目录；folderId 传 0 表示退回未归档。 */
    @Query("UPDATE duty_log SET folderId = :folderId, updatedAt = :updatedAt WHERE epochDay IN (:days)")
    void moveToFolder(List<Long> days, long folderId, long updatedAt);

    /** 统计某目录内的日志数量。 */
    @Query("SELECT COUNT(*) FROM duty_log WHERE folderId = :folderId")
    int countInFolder(long folderId);

    // ===== 目录 =====

    /** 全部目录，新的在前。 */
    @Query("SELECT * FROM log_folder ORDER BY createdAt DESC")
    List<LogFolder> getFolders();

    /** 按名字查目录，用于避免重名。 */
    @Query("SELECT * FROM log_folder WHERE name = :name LIMIT 1")
    LogFolder findFolderByName(String name);

    @Insert
    long insertFolder(LogFolder folder);

    /** 删除目录。 */
    @Query("DELETE FROM log_folder WHERE id = :id")
    void deleteFolder(long id);

    /** 重命名目录。 */
    @Query("UPDATE log_folder SET name = :name WHERE id = :id")
    void renameFolder(long id, String name);

    /** 目录删除后，把里面的日志退回未归档。 */
    @Query("UPDATE duty_log SET folderId = 0 WHERE folderId = :folderId")
    void unfileFolder(long folderId);

    /** 删除目录时连里面的日志一起删掉。 */
    @Query("DELETE FROM duty_log WHERE folderId = :folderId")
    void deleteLogsInFolder(long folderId);

    /** 单条日志移出归档。 */
    @Query("UPDATE duty_log SET folderId = 0, updatedAt = :updatedAt WHERE epochDay = :day")
    void unfileDay(long day, long updatedAt);

    /** 某个目录里的日志数量与最近日期，用于归档列表展示。 */
    @Query("SELECT COUNT(*) FROM duty_log WHERE folderId = :folderId")
    int countLogsInFolder(long folderId);

    /** 某个目录里最新的日期。 */
    @Query("SELECT MAX(epochDay) FROM duty_log WHERE folderId = :folderId")
    Long latestDayInFolder(long folderId);
}
