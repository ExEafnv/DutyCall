package com.eafnv.callforward.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface PersonDao {
    @Query("SELECT * FROM person ORDER BY sortOrder ASC, id ASC")
    List<Person> getAll();

    @Query("SELECT * FROM person WHERE id = :id")
    Person getById(long id);

    @Query("SELECT COUNT(*) FROM person")
    int count();

    @Query("SELECT MAX(sortOrder) FROM person")
    Integer maxSortOrder();

    @Insert
    long insert(Person person);

    @Update
    void update(Person person);

    @Delete
    void delete(Person person);
}