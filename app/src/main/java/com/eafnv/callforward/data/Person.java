package com.eafnv.callforward.data;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

/**
 * 值班人员：排班的候选对象，一人对应一个手机号。
 */
@Entity(tableName = "person")
public class Person {
    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 显示名，例如“张三”。 */
    public String name;
    /** 手机号，转移指令实际拨向的号码。 */
    public String phoneNumber;
    /** 运营商覆盖项，为空时跟随全局设置。 */
    public String carrierOverride;
    /** 列表显示顺序，数值小的排前面。 */
    public int sortOrder;
    /** 是否参与排班，停用后不会被选中。 */
    public boolean enabled = true;

    /** Room 实例化用的空构造。 */
    public Person() {}

    /** 新建人员，只填姓名和号码。 */
    @Ignore
    public Person(String name, String phoneNumber) {
        this.name = name;
        this.phoneNumber = phoneNumber;
    }
}