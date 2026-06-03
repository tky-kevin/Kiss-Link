package com.kisslink.data.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * KissLink 的 Room 資料庫單例。
 *
 * <p>版本策略：每次 schema 有破壞性變更時遞增 {@code version}，
 * 並在 {@code fallbackToDestructiveMigration()} 允許的情況下清除舊資料（開發期）。
 * 正式版應實作 {@link androidx.room.migration.Migration}。
 */
@Database(
        entities = {TransferRecordEntity.class},
        version  = 1,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase instance;

    public abstract TransferDao transferDao();

    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "kisslink.db")
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return instance;
    }
}
