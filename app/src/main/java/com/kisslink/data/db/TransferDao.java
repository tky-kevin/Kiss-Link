package com.kisslink.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * 傳輸紀錄的 Room DAO。
 */
@Dao
public interface TransferDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(TransferRecordEntity record);

    /** 取得所有紀錄，按時間降序。 */
    @Query("SELECT * FROM transfer_records ORDER BY timestampMs DESC")
    LiveData<List<TransferRecordEntity>> getAllRecords();

    /** 取得最近 N 筆。 */
    @Query("SELECT * FROM transfer_records ORDER BY timestampMs DESC LIMIT :limit")
    LiveData<List<TransferRecordEntity>> getRecentRecords(int limit);

    @Query("DELETE FROM transfer_records WHERE id = :id")
    void deleteById(long id);

    @Query("DELETE FROM transfer_records")
    void deleteAll();

    @Query("SELECT COUNT(*) FROM transfer_records")
    int getCount();
}
