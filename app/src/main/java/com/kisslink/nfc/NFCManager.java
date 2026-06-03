package com.kisslink.nfc;

import android.app.Activity;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.util.Log;

import com.kisslink.model.GroupCredential;

import java.io.IOException;

/**
 * 接收方使用的 NFC Reader 管理器。
 *
 * <p>啟用 NFC Reader 模式，當偵測到 HCE 卡片（傳送方手機）時：
 * <ol>
 *   <li>發送 SELECT AID APDU（{@link APDUHelper#buildSelectAidApdu()}）。</li>
 *   <li>讀取包含憑證 JSON 的回應。</li>
 *   <li>反序列化為 {@link GroupCredential} 並透過 {@link OnCredentialReceived} 回呼。</li>
 * </ol>
 *
 * <h3>使用方式（在 PairingActivity）</h3>
 * <pre>
 *   // onResume:
 *   nfcManager.enableReaderMode(this, cred -> viewModel.onNfcCredentialReceived(cred));
 *   // onPause:
 *   nfcManager.disableReaderMode(this);
 * </pre>
 */
public class NFCManager {

    private static final String TAG = "NFCManager";

    public interface OnCredentialReceived {
        void onReceived(GroupCredential credential);
    }

    public interface OnNfcError {
        void onError(String message);
    }

    private final NfcAdapter nfcAdapter;

    public NFCManager(Activity activity) {
        this.nfcAdapter = NfcAdapter.getDefaultAdapter(activity);
    }

    /** 裝置是否支援 NFC。 */
    public boolean isNfcAvailable() {
        return nfcAdapter != null;
    }

    /** NFC 功能是否已由使用者開啟。 */
    public boolean isNfcEnabled() {
        return nfcAdapter != null && nfcAdapter.isEnabled();
    }

    // ══════════════════════════════════════════════════════════
    //  Reader 模式
    // ══════════════════════════════════════════════════════════

    /**
     * 啟用前景 NFC Reader 模式。在 Activity.onResume() 呼叫。
     *
     * @param activity  持有前景焦點的 Activity
     * @param onReceived 成功讀取憑證後的回呼（在 NFC 執行緒呼叫）
     * @param onError    讀取失敗時的回呼
     */
    public void enableReaderMode(Activity activity,
                                 OnCredentialReceived onReceived,
                                 OnNfcError onError) {
        if (!isNfcAvailable()) {
            if (onError != null) onError.onError("此裝置不支援 NFC");
            return;
        }
        if (!isNfcEnabled()) {
            if (onError != null) onError.onError("請先在設定中開啟 NFC");
            return;
        }

        nfcAdapter.enableReaderMode(
                activity,
                tag -> handleTag(tag, onReceived, onError),
                // 僅掃描 ISO-DEP（HCE 使用的協定），跳過 NDEF 標籤處理
                NfcAdapter.FLAG_READER_NFC_A
                        | NfcAdapter.FLAG_READER_NFC_B
                        | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                null // extras
        );
        Log.d(TAG, "NFC Reader mode enabled");
    }

    /** 停用前景 NFC Reader 模式。在 Activity.onPause() 呼叫。 */
    public void disableReaderMode(Activity activity) {
        if (nfcAdapter != null) {
            nfcAdapter.disableReaderMode(activity);
            Log.d(TAG, "NFC Reader mode disabled");
        }
    }

    // ══════════════════════════════════════════════════════════
    //  標籤處理
    // ══════════════════════════════════════════════════════════

    private void handleTag(Tag tag, OnCredentialReceived onReceived, OnNfcError onError) {
        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) {
            Log.w(TAG, "Tag does not support ISO-DEP");
            if (onError != null) onError.onError("不支援的 NFC 標籤類型");
            return;
        }

        try {
            isoDep.connect();
            isoDep.setTimeout(3000); // 3 秒逾時

            // 1. 發送 SELECT AID
            byte[] selectApdu = APDUHelper.buildSelectAidApdu();
            Log.d(TAG, "Sending SELECT AID...");
            byte[] response = isoDep.transceive(selectApdu);

            // 2. 解析回應
            byte[] payload = APDUHelper.extractPayload(response);
            if (payload == null) {
                Log.e(TAG, "Invalid APDU response: " + bytesToHex(response));
                if (onError != null) onError.onError("NFC 回應格式錯誤，請確認對方已就緒");
                return;
            }

            // 3. 反序列化憑證
            GroupCredential credential = NFCCredential.fromBytes(payload);
            Log.i(TAG, "Credential received via NFC: " + credential);
            if (onReceived != null) onReceived.onReceived(credential);

        } catch (IOException e) {
            Log.e(TAG, "IsoDep transceive error", e);
            if (onError != null) onError.onError("NFC 通訊失敗，請再試一次");
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Credential parse error", e);
            if (onError != null) onError.onError("憑證格式錯誤：" + e.getMessage());
        } finally {
            try { isoDep.close(); } catch (IOException ignored) {}
        }
    }

    private static String bytesToHex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format("%02X ", v));
        return sb.toString().trim();
    }
}
