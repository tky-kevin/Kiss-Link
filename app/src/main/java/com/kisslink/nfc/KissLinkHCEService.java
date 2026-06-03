package com.kisslink.nfc;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.util.Log;

import com.kisslink.model.GroupCredential;

/**
 * NFC Host Card Emulation 服務（傳送方使用）。
 *
 * <p>本裝置模擬一張 NFC 卡片，當接收方的手機靠近讀取時，
 * 回傳 Wi-Fi Direct 連線憑證（SSID / Passphrase / IP / Port）。
 *
 * <h3>使用流程</h3>
 * <ol>
 *   <li>傳送方建立 Wi-Fi Direct Group 並取得憑證。</li>
 *   <li>呼叫 {@link #setCredential(GroupCredential)} 注入憑證。</li>
 *   <li>接收方碰觸 → HCE 收到 SELECT AID APDU →
 *       回傳 JSON 憑證 bytes + SW_OK。</li>
 *   <li>接收方解析後呼叫 {@code WifiDirectManager.connectAsClient()}。</li>
 * </ol>
 *
 * <h3>AndroidManifest 宣告</h3>
 * <pre>{@code
 * <service android:name=".nfc.KissLinkHCEService"
 *     android:exported="true"
 *     android:permission="android.permission.BIND_NFC_SERVICE">
 *     <intent-filter>
 *         <action android:name="android.nfc.cardemulation.action.HOST_APDU_SERVICE"/>
 *     </intent-filter>
 *     <meta-data android:name="android.nfc.cardemulation.host_apdu_service"
 *         android:resource="@xml/apduservice"/>
 * </service>
 * }</pre>
 */
public class KissLinkHCEService extends HostApduService {

    private static final String TAG = "KissLinkHCEService";

    /** 靜態持有憑證，讓 Activity/ViewModel 在 Service 啟動前就可設置。 */
    private static volatile byte[] pendingCredentialBytes = null;

    // ══════════════════════════════════════════════════════════
    //  靜態 API（供 PairingViewModel 呼叫）
    // ══════════════════════════════════════════════════════════

    /**
     * 注入待廣播的憑證。
     * 必須在碰觸發生前呼叫（通常在 createGroupAsGO 成功後）。
     */
    public static void setCredential(GroupCredential credential) {
        pendingCredentialBytes = NFCCredential.toBytes(credential);
        Log.d(TAG, "Credential set: " + credential.getSsid()
                + " (" + pendingCredentialBytes.length + " bytes)");
    }

    /** 清除憑證（傳輸完成或取消後呼叫）。 */
    public static void clearCredential() {
        pendingCredentialBytes = null;
        Log.d(TAG, "Credential cleared");
    }

    // ══════════════════════════════════════════════════════════
    //  HostApduService 回調
    // ══════════════════════════════════════════════════════════

    /**
     * 處理來自讀卡器的 APDU 指令。
     *
     * <p>此方法在系統的 NFC 執行緒呼叫，必須快速返回（不可執行 I/O）。
     */
    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        if (apdu == null) return APDUHelper.SW_ERROR;

        Log.d(TAG, "APDU received: " + bytesToHex(apdu));

        if (!APDUHelper.isSelectAid(apdu)) {
            Log.w(TAG, "Unknown APDU command");
            return APDUHelper.SW_UNKNOWN_COMMAND;
        }

        // 確認憑證已就緒
        byte[] payload = pendingCredentialBytes;
        if (payload == null) {
            Log.w(TAG, "Credential not ready");
            return APDUHelper.SW_CONDITIONS_NOT_MET;
        }

        byte[] response = APDUHelper.buildOkResponse(payload);
        Log.i(TAG, "Responding with credential (" + payload.length + " bytes)");
        return response;
    }

    @Override
    public void onDeactivated(int reason) {
        String reasonStr = (reason == DEACTIVATION_LINK_LOSS) ? "LINK_LOSS" : "DESELECTED";
        Log.d(TAG, "HCE deactivated: " + reasonStr);
    }

    // ══════════════════════════════════════════════════════════
    //  工具
    // ══════════════════════════════════════════════════════════

    private static String bytesToHex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format("%02X ", v));
        return sb.toString().trim();
    }
}
