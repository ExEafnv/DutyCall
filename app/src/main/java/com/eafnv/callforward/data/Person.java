package com.eafnv.callforward.data;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "person")
public class Person {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public String name;
    public String phoneNumber;
    public String carrierOverride;
    public int sortOrder;
    public boolean enabled = true;

    public Person() {}

    @Ignore
    public Person(String name, String phoneNumber) {
        this.name = name;
        this.phoneNumber = phoneNumber;
    }
}