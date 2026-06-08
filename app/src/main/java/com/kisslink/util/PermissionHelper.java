package com.kisslink.util;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 權限請求工具類。
 */
public class PermissionHelper {

    public static final int REQUEST_CODE = 1001;

    /**
     * 取得 Wi-Fi Direct 與檔案傳輸所需的權限清單。
     */
    public static String[] getRequiredPermissions() {
        List<String> permissions = new ArrayList<>();

        // 基礎 Wi-Fi 與位置 (API 29+)
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_WIFI_STATE);
        permissions.add(Manifest.permission.CHANGE_WIFI_STATE);

        // API 33+ 需要 NEARBY_WIFI_DEVICES 與 POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        // API 31+：BLE 憑證側通道需要的新版藍牙執行期權限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
        }

        // 前景服務 (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE);
        }
        
        // API 34+ 前景服務類型權限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC);
        }

        return permissions.toArray(new String[0]);
    }

    /**
     * 檢查是否已取得所有必要權限。
     */
    public static boolean hasPermissions(@NonNull Context context) {
        for (String p : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(context, p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * 請求權限。
     */
    public static void requestPermissions(@NonNull Activity activity) {
        ActivityCompat.requestPermissions(activity, getRequiredPermissions(), REQUEST_CODE);
    }

    /**
     * 在 {@code onRequestPermissionsResult} 中呼叫，判斷是否全部授予。
     */
    public static boolean allGranted(@NonNull int[] grantResults) {
        if (grantResults.length == 0) return false;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }
}
