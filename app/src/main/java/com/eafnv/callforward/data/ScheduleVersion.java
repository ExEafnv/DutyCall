package com.eafnv.callforward.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 排班版本：从某个生效日期起生效的一整套排班规则。
 *
 * 历史日期仍按当时的版本解析，因此改排班是新增版本，而不是改旧版本。
 */
@Entity(tableName = "schedule_version")
public class ScheduleVersion {
    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 排班模式，取 ScheduleMode 里的常量。 */
    public int mode;
    /** 从这一天起生效，按 epochDay 存储。 */
    public long effectiveFromEpochDay;
    /** 锚点周的周一，轮转从这里开始算。 */
    public long anchorMondayEpochDay;
    /** 锚点周一当天值班人的 id。 */
    public long anchorPersonId;
    /** 自定义模式的扩展参数，JSON 文本，存分组大小、分段定义等。 */
    public String extraParams;
    /** 引用的 Formula.id，为 0 表示没有引用公式。 */
    public long formulaId;
    /** 公式备注，排班页展示用，公式被删后仍能看出当时用的什么。 */
    public String formulaRemark;
    /** 版本创建时间，毫秒时间戳。 */
    public long createdAt;

    /** Room 实例化用的空构造。 */
    public ScheduleVersion() {}
}