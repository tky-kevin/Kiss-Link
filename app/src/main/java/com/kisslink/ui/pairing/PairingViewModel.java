package com.kisslink.ui.pairing;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.kisslink.model.GroupCredential;
import com.kisslink.nfc.KissLinkHCEService;
import com.kisslink.wifidirect.ConnectionState;
import com.kisslink.wifidirect.WifiDirectManager;

/**
 * PairingActivity 的 ViewModel（M1 + M3 完整整合版）。
 *
 * <h3>SENDER 流程</h3>
 * <pre>
 *   init(SENDER) → createGroupAsGO()
 *     → credential ready → KissLinkHCEService.setCredential()
 *     → 等待 NFC 碰觸（HCE 廣播憑證）
 *     → 對方 Wi-Fi Direct 連線 → state = CONNECTED
 * </pre>
 *
 * <h3>RECEIVER 流程</h3>
 * <pre>
 *   init(RECEIVER) → NFCManager.enableReaderMode()（由 Activity 操作）
 *     → NFC 碰觸 → onNfcCredentialReceived(cred)
 *     → connectAsClient(cred) → state = CONNECTED
 * </pre>
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class PairingViewModel extends AndroidViewModel {

    private static final String TAG = "PairingViewModel";

    public enum Role { SENDER, RECEIVER }

    private Role role;
    private GroupCredential lastCredential; // 最後取得的憑證（供 Activity 啟動 Service）

    private final WifiDirectManager wifiDirectManager;

    // ══════════════════════════════════════════════════════════
    //  建構子
    // ══════════════════════════════════════════════════════════

    public PairingViewModel(@NonNull Application application) {
        super(application);
        wifiDirectManager = new WifiDirectManager(application);
    }

    // ══════════════════════════════════════════════════════════
    //  初始化
    // ══════════════════════════════════════════════════════════

    /**
     * 根據角色啟動對應流程。每個 ViewModel 實例只執行一次。
     *
     * @param role    傳送方 / 接收方
     * @param context 用於 NFC Manager 初始化（Activity context）
     */
    public void init(@NonNull Role role, @NonNull Context context) {
        if (this.role != null) return; // 防止重複初始化（螢幕旋轉）
        this.role = role;
        Log.i(TAG, "init role=" + role);

        if (role == Role.SENDER) {
            startAsSender();
        }
        // RECEIVER 的 NFC enableReaderMode 在 Activity.onResume 呼叫（需要 Activity 引用）
    }

    // ══════════════════════════════════════════════════════════
    //  SENDER 流程
    // ══════════════════════════════════════════════════════════

    private void startAsSender() {
        // 1. 建立 Wi-Fi Direct Group
        wifiDirectManager.createGroupAsGO();

        // 2. 憑證就緒後注入 HCEService，開始對外廣播
        wifiDirectManager.getCredential().observeForever(cred -> {
            if (cred == null) return;
            lastCredential = cred;
            KissLinkHCEService.setCredential(cred);
            Log.i(TAG, "HCEService credential set: " + cred.getSsid());
        });
    }

    // ══════════════════════════════════════════════════════════
    //  RECEIVER 流程（NFC 回呼入口）
    // ══════════════════════════════════════════════════════════

    /**
     * 由 PairingActivity 在 NFCManager 回呼中呼叫（NFC 碰觸成功後）。
     * 觸發 Wi-Fi Direct 連線。
     */
    public void onNfcCredentialReceived(@NonNull GroupCredential credential) {
        Log.i(TAG, "NFC credential received: " + credential);
        lastCredential = credential;
        wifiDirectManager.connectAsClient(credential);
    }

    // ══════════════════════════════════════════════════════════
    //  BroadcastReceiver 代理
    // ══════════════════════════════════════════════════════════

    public void registerReceiver(@NonNull Context ctx) {
        wifiDirectManager.registerReceiver(ctx);
    }

    public void unregisterReceiver(@NonNull Context ctx) {
        wifiDirectManager.unregisterReceiver(ctx);
    }

    // ══════════════════════════════════════════════════════════
    //  LiveData Getters
    // ══════════════════════════════════════════════════════════

    public LiveData<ConnectionState> getConnectionState() {
        return wifiDirectManager.getState();
    }

    public LiveData<GroupCredential> getCredential() {
        return wifiDirectManager.getCredential();
    }

    public LiveData<String> getError() {
        return wifiDirectManager.getError();
    }

    public Role           getRole()           { return role; }

    /** 最後取得的憑證，供 Activity 傳遞給 FileTransferService。 */
    @Nullable
    public GroupCredential getLastCredential() { return lastCredential; }

    // ══════════════════════════════════════════════════════════
    //  清理
    // ══════════════════════════════════════════════════════════

    @Override
    protected void onCleared() {
        super.onCleared();
        KissLinkHCEService.clearCredential();
        wifiDirectManager.reset();
        Log.d(TAG, "PairingViewModel cleared");
    }
}
