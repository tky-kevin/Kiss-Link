package com.kisslink.transfer;

import androidx.annotation.Nullable;

/**
 * 逐檔完成事件的回呼——由 {@link TransferServer} / {@link TransferClient} 在
 * 每個檔案真正結束（成功或失敗）的那一點**同步**呼叫一次。
 *
 * <p>歷史紀錄不可掛在進度 {@code LiveData} 上：{@code postValue} 在主執行緒處理前
 * 會合併（conflate）連續事件，導致小檔快速連傳時部分 {@code FILE_DONE} 被丟棄、
 * 紀錄數量短少。本回呼直接在傳輸迴圈內觸發，保證一檔一次、不漏不重。
 */
public interface TransferEventListener {

    /**
     * @param fileName     檔名
     * @param sizeBytes    檔案大小
     * @param avgSpeedBps  平均速度（bytes/sec），無法計算時為 0
     * @param success      是否成功完成
     * @param fileUri      接收方：MediaStore URI 字串；傳送方傳 null
     */
    void onFileCompleted(String fileName, long sizeBytes, long avgSpeedBps,
                         boolean success, @Nullable String fileUri);
}
