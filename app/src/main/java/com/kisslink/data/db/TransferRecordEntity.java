package com.kisslink.data.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Room 資料庫的傳輸紀錄實體，對應 {@code transfer_records} 表。
 */
@Entity(tableName = "transfer_records")
public class TransferRecordEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 傳送 / 接收 */
    public String direction; // "SEND" or "RECEIVE"

    /** 對方裝置名稱（暫存，M6 可擴充）*/
    public String peerDeviceName;

    /** 檔案名稱 */
    public String fileName;

    /** 檔案大小（bytes）*/
    public long fileSizeBytes;

    /** 傳輸完成 / 失敗時間（Unix timestamp ms）*/
    public long timestampMs;

    /** 是否成功完成 */
    public boolean success;

    /** 平均傳輸速度（bytes/sec）*/
    public long avgSpeedBps;

    /** 儲存路徑（接收方）或 URI（傳送方） */
    public String filePath;
}
