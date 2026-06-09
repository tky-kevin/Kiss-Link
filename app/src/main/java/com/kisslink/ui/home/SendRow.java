package com.kisslink.ui.home;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.kisslink.transfer.TransferProtocol;

/**
 * Beam 清單一列的顯示資料（已選待送 / 傳輸中 / 接收中皆共用）。
 */
public final class SendRow {
    public final String name;
    public final String sizeLabel;     // 例如 "4.2 MB"，未知為 ""
    public final byte   itemType;      // TransferProtocol.ITEM_*
    @Nullable public final Uri thumbUri;  // 相片/影片縮圖來源（檔案為 null）

    public boolean done = false;
    public int     percent = -1;       // 傳輸中 0..100；-1 表示尚未/不適用
    public boolean incoming = false;   // true=接收中（對方送來）
    public boolean removable = false;  // true=待傳清單，顯示移除鈕

    public SendRow(String name, String sizeLabel, byte itemType, @Nullable Uri thumbUri) {
        this.name = name;
        this.sizeLabel = sizeLabel == null ? "" : sizeLabel;
        this.itemType = itemType;
        this.thumbUri = thumbUri;
    }

    public boolean isVisualMedia() {
        return itemType == TransferProtocol.ITEM_PHOTO;
    }
}
