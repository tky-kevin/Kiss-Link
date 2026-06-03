package com.kisslink.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

/**
 * 檔案相關工具函式（SAF / ContentResolver 適配）。
 */
public final class FileUtils {

    private FileUtils() {}

    /** 從 SAF Uri 取得顯示名稱（檔名）。 */
    public static String getFileName(ContentResolver cr, Uri uri) {
        String result = null;
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            try (Cursor c = cr.query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (col >= 0) result = c.getString(col);
                }
            }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result != null ? result : "unknown_file";
    }

    /** 從 SAF Uri 取得檔案大小（bytes），若無法取得回傳 -1。 */
    public static long getFileSize(ContentResolver cr, Uri uri) {
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            try (Cursor c = cr.query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int col = c.getColumnIndex(OpenableColumns.SIZE);
                    if (col >= 0 && !c.isNull(col)) return c.getLong(col);
                }
            }
        }
        return -1;
    }

    /** 格式化位元組數為人類可讀字串，例如「12.3 MB」。 */
    public static String formatSize(long bytes) {
        if (bytes < 0)         return "未知大小";
        if (bytes < 1024)      return bytes + " B";
        if (bytes < 1024*1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L*1024*1024) return String.format("%.1f MB", bytes / (1024.0*1024));
        return String.format("%.2f GB", bytes / (1024.0*1024*1024));
    }

    /** 依副檔名推測圖示資源 ID（可擴充）。 */
    public static int guessIcon(String fileName) {
        if (fileName == null) return android.R.drawable.ic_menu_save;
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".gif") || lower.endsWith(".webp"))
            return android.R.drawable.ic_menu_gallery;
        if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi"))
            return android.R.drawable.ic_media_play;
        if (lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".aac"))
            return android.R.drawable.ic_media_play;
        if (lower.endsWith(".pdf"))
            return android.R.drawable.ic_menu_agenda;
        if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z"))
            return android.R.drawable.ic_menu_save;
        return android.R.drawable.ic_menu_save;
    }
}
