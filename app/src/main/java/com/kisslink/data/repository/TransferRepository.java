package com.kisslink.data.repository;

import android.content.Context;

import androidx.lifecycle.LiveData;

import com.kisslink.data.db.AppDatabase;
import com.kisslink.data.db.TransferDao;
import com.kisslink.data.db.TransferRecordEntity;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 傳輸紀錄的資料存取層（Repository Pattern）。
 *
 * <p>所有寫入操作均在背景執行緒執行（Room 不允許主執行緒 DB 操作）。
 * 讀取操作透過 LiveData 自動在背景查詢並切換到主執行緒通知觀察者。
 */
public class TransferRepository {

    private static TransferRepository instance;

    private final TransferDao dao;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TransferRepository(Context context) {
        dao = AppDatabase.getInstance(context).transferDao();
    }

    public static TransferRepository getInstance(Context context) {
        if (instance == null) {
            synchronized (TransferRepository.class) {
                if (instance == null) instance = new TransferRepository(context);
            }
        }
        return instance;
    }

    // ── 寫入 ──────────────────────────────────────────────────

    public void insert(TransferRecordEntity record) {
        executor.execute(() -> dao.insert(record));
    }

    public void delete(long id) {
        executor.execute(() -> dao.deleteById(id));
    }

    public void clearAll() {
        executor.execute(dao::deleteAll);
    }

    // ── 讀取（LiveData，主執行緒安全）────────────────────────────

    public LiveData<List<TransferRecordEntity>> getAllRecords() {
        return dao.getAllRecords();
    }

    public LiveData<List<TransferRecordEntity>> getRecentRecords(int limit) {
        return dao.getRecentRecords(limit);
    }

    // ── 工廠方法：從傳輸進度建立紀錄 ──────────────────────────────

    public TransferRecordEntity buildRecord(String direction, String fileName,
                                            long sizeBytes, boolean success,
                                            long avgSpeedBps, String filePath) {
        TransferRecordEntity e = new TransferRecordEntity();
        e.direction    = direction;
        e.fileName     = fileName;
        e.fileSizeBytes= sizeBytes;
        e.success      = success;
        e.avgSpeedBps  = avgSpeedBps;
        e.filePath     = filePath;
        e.timestampMs  = System.currentTimeMillis();
        return e;
    }
}
