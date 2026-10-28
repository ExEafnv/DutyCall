package com.eafnv.callforward.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * 应用的 Room 数据库入口，包含人员、排班版本、单日换班、公式与交接日志等表。
 *
 * 单例加双检锁，避免多线程同时建库。
 */
@Database(
        entities = {Person.class, ScheduleVersion.class, DayOverride.class,
                Formula.class, DutyLog.class, LogFolder.class},
        version = 4,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    /** 数据库单例，第一次访问时创建。 */
    private static volatile AppDatabase INSTANCE;

    /** 人员表访问接口。 */
    public abstract PersonDao personDao();
    /** 排班版本与单日换班访问接口。 */
    public abstract ScheduleDao scheduleDao();
    /** 公式库访问接口。 */
    public abstract FormulaDao formulaDao();
    /** 交接日志与日志目录访问接口。 */
    public abstract LogDao logDao();

    /** v3：新增公式库表，并给排班版本加上公式引用与备注。 */
    private static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `formula` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`name` TEXT, "
                    + "`expression` TEXT, "
                    + "`createdAt` INTEGER NOT NULL)");
            db.execSQL("ALTER TABLE `schedule_version` ADD COLUMN `formulaId` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `schedule_version` ADD COLUMN `formulaRemark` TEXT");
        }
    };

    /** v4：新增交接日志表与日志目录表。 */
    private static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `duty_log` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`epochDay` INTEGER NOT NULL, "
                    + "`content` TEXT, "
                    + "`folderId` INTEGER NOT NULL DEFAULT 0, "
                    + "`createdAt` INTEGER NOT NULL, "
                    + "`updatedAt` INTEGER NOT NULL)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS "
                    + "`index_duty_log_epochDay` ON `duty_log` (`epochDay`)");
            db.execSQL("CREATE TABLE IF NOT EXISTS `log_folder` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`name` TEXT, "
                    + "`createdAt` INTEGER NOT NULL)");
        }
    };

    /** 取数据库单例，首次调用时建库并执行迁移。 */
    public static AppDatabase get(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "dutycall.db"
                    ).addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                            .fallbackToDestructiveMigration().build();
                }
            }
        }
        return INSTANCE;
    }
}
