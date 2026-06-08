package com.kisslink.nfc;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;

import com.kisslink.model.GroupCredential;
import com.kisslink.pairing.BootstrapCodec;
import com.kisslink.pairing.PairingToken;

/**
 * NFC Host Card Emulation——模擬一張 <b>NFC Forum Type-4 標籤</b>,對外吐出
 * 含 deep link + {@link PairingToken} 的 NDEF 訊息。
 *
 * <h3>為何改成 NDEF 標籤模擬(而非舊的自訂 AID + APDU 交換)?</h3>
 * <p>舊方式用自訂 AID {@code F04B495353},只有「已開 App 且在 reader 模式」的對方才讀得到,
 * 無法讓未開 App 的手機被 OS dispatch 啟動。改用標準 NDEF Type-4 AID
 * {@code D2760000850101} 後,對方手機(即使 App 沒開、螢幕亮著已解鎖)會由系統預設 NFC
 * dispatch 讀到 NDEF,經 deep link {@code kisslink://pair?...} 啟動本 App(情境 1);
 * 已開 App 的對方則用自己的 reader 直接解析 token(情境 2)。一張標籤兩種讀法皆可。
 *
 * <h3>Type-4 讀取序列(對方 reader / OS 發出)</h3>
 * <pre>
 *   SELECT AID (D2760000850101)      → 90 00
 *   SELECT CC file (E103)            → 90 00
 *   READ BINARY CC                   → [15-byte CC] 90 00
 *   SELECT NDEF file (E104)          → 90 00
 *   READ BINARY NLEN (offset 0,len2) → [NDEF 長度] 90 00
 *   READ BINARY NDEF (offset 2,...)  → [NDEF message] 90 00
 * </pre>
 */
public class KissLinkHCEService extends HostApduService {

    private static final String TAG = "KissLinkHCEService";

    // ── Type-4 常數 ────────────────────────────────────────────
    private static final byte[] NDEF_AID =
            {(byte) 0xD2, 0x76, 0x00, 0x00, (byte) 0x85, 0x01, 0x01};

    /** Capability Container 檔(E103);宣告 NDEF 檔位置/大小/唯讀。 */
    private static final byte[] CC_FILE = {
            (byte) 0x00, (byte) 0x0F,             // CCLEN = 15
            (byte) 0x20,                          // Mapping Version 2.0
            (byte) 0x00, (byte) 0xFB,             // MLe = 251 (一次最多回 251 bytes)
            (byte) 0x00, (byte) 0xFB,             // MLc = 251
            (byte) 0x04, (byte) 0x06,             // NDEF File Control TLV: T=04, L=06
            (byte) 0xE1, (byte) 0x04,             //   NDEF file id = E104
            (byte) 0x0F, (byte) 0xFF,             //   max NDEF size = 4095
            (byte) 0x00,                          //   read access granted
            (byte) 0xFF                           //   write access denied (read-only)
    };

    private static final byte[] CC_FILE_ID   = {(byte) 0xE1, 0x03};
    private static final byte[] NDEF_FILE_ID = {(byte) 0xE1, 0x04};

    // ── Status Words ───────────────────────────────────────────
    private static final byte[] SW_OK                = {(byte) 0x90, 0x00};
    private static final byte[] SW_FILE_NOT_FOUND    = {(byte) 0x6A, (byte) 0x82};
    private static final byte[] SW_WRONG_P1P2        = {(byte) 0x6B, 0x00};
    private static final byte[] SW_INS_NOT_SUPPORTED = {(byte) 0x6D, 0x00};
    private static final byte[] SW_ERROR             = {(byte) 0x6F, 0x00};

    // ── 選中檔狀態 ─────────────────────────────────────────────
    private static final int FILE_NONE = 0, FILE_CC = 1, FILE_NDEF = 2;
    private int selectedFile = FILE_NONE;
    private boolean readFired = false; // 本次活化是否已通知「被讀」

    // ══════════════════════════════════════════════════════════
    //  靜態 API（供配對控制器設置 / 監聽）
    // ══════════════════════════════════════════════════════════

    /** 當前要吐出的 NDEF 檔(含前 2 byte NLEN)。 */
    private static volatile byte[] ndefFile = null;

    /** 被讀(latch 為 tag)時的通知;在 NFC 執行緒呼叫,實作端自行切回主執行緒。 */
    public interface OnTagReadListener { void onTagRead(); }
    @Nullable private static volatile OnTagReadListener readListener;

    /** 設定本機這場配對要對外廣播的 token，AAR 使用指定 packageName。 */
    public static void setActiveToken(PairingToken token, String packageName) {
        byte[] msg = BootstrapCodec.buildNdefBytes(token, packageName);
        byte[] file = new byte[msg.length + 2];
        file[0] = (byte) ((msg.length >> 8) & 0xFF);
        file[1] = (byte) (msg.length & 0xFF);
        System.arraycopy(msg, 0, file, 2, msg.length);
        ndefFile = file;
        Log.d(TAG, "Active token set, NDEF file " + file.length + " bytes");
    }

    /** @deprecated 改用 {@link #setActiveToken(PairingToken, String)}。 */
    @Deprecated
    public static void setActiveToken(PairingToken token) {
        setActiveToken(token, BootstrapCodec.APP_PACKAGE);
    }

    public static void clearToken() {
        ndefFile = null;
        Log.d(TAG, "Active token cleared");
    }

    public static void setOnTagReadListener(@Nullable OnTagReadListener l) {
        readListener = l;
    }

    // ── 暫時相容橋接（Stage 4 重寫 FileTransferService 時移除）──────
    /** @deprecated 舊憑證注入流程;NDEF 配對不再使用。暫留以維持編譯。 */
    @Deprecated
    public static void setCredential(GroupCredential credential) { /* no-op */ }

    /** @deprecated 舊清除流程;改用 {@link #clearToken()}。暫留以維持編譯。 */
    @Deprecated
    public static void clearCredential() { clearToken(); }

    // ══════════════════════════════════════════════════════════
    //  HostApduService 回調
    // ══════════════════════════════════════════════════════════

    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        if (apdu == null || apdu.length < 4) return SW_ERROR;

        int ins = apdu[1] & 0xFF;
        switch (ins) {
            case 0xA4: return handleSelect(apdu);
            case 0xB0: return handleReadBinary(apdu);
            default:   return SW_INS_NOT_SUPPORTED;
        }
    }

    private byte[] handleSelect(byte[] apdu) {
        int p1 = apdu[2] & 0xFF;
        int p2 = apdu[3] & 0xFF;

        // SELECT by AID (P1=04, P2=00)
        if (p1 == 0x04 && p2 == 0x00) {
            if (apdu.length < 6) return SW_WRONG_P1P2;
            int lc = apdu[4] & 0xFF;
            if (apdu.length < 5 + lc) return SW_WRONG_P1P2;
            if (matches(apdu, 5, NDEF_AID)) {
                selectedFile = FILE_NONE;
                readFired = false;
                Log.d(TAG, "SELECT NDEF application");
                return SW_OK;
            }
            return SW_FILE_NOT_FOUND;
        }

        // SELECT by file id (P1=00, P2=0C, Lc=02)
        if (p1 == 0x00 && p2 == 0x0C) {
            if (apdu.length < 7) return SW_WRONG_P1P2;
            if (matches(apdu, 5, CC_FILE_ID))   { selectedFile = FILE_CC;   return SW_OK; }
            if (matches(apdu, 5, NDEF_FILE_ID)) { selectedFile = FILE_NDEF; return SW_OK; }
            return SW_FILE_NOT_FOUND;
        }

        return SW_FILE_NOT_FOUND;
    }

    private byte[] handleReadBinary(byte[] apdu) {
        byte[] file = (selectedFile == FILE_CC) ? CC_FILE
                : (selectedFile == FILE_NDEF) ? ndefFile : null;
        if (file == null) return SW_FILE_NOT_FOUND;

        int offset = ((apdu[2] & 0xFF) << 8) | (apdu[3] & 0xFF);
        int le = apdu.length >= 5 ? (apdu[4] & 0xFF) : 0;
        if (le == 0) le = 256;

        if (offset > file.length) return SW_WRONG_P1P2;
        int len = Math.min(le, file.length - offset);

        byte[] resp = new byte[len + 2];
        System.arraycopy(file, offset, resp, 0, len);
        resp[len]     = SW_OK[0];
        resp[len + 1] = SW_OK[1];

        // 對方已把 NDEF 檔讀到尾 → 視為 latch 為 tag,通知一次。
        if (selectedFile == FILE_NDEF && !readFired && offset + len >= file.length) {
            readFired = true;
            OnTagReadListener l = readListener;
            if (l != null) {
                try { l.onTagRead(); } catch (Exception e) { Log.w(TAG, "readListener error", e); }
            }
            Log.i(TAG, "NDEF fully read by peer → tag latched");
        }
        return resp;
    }

    @Override
    public void onDeactivated(int reason) {
        selectedFile = FILE_NONE;
        readFired = false;
        Log.d(TAG, "HCE deactivated: "
                + (reason == DEACTIVATION_LINK_LOSS ? "LINK_LOSS" : "DESELECTED"));
    }

    // ── 工具 ──────────────────────────────────────────────────

    private static boolean matches(byte[] apdu, int off, byte[] expected) {
        if (apdu.length < off + expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (apdu[off + i] != expected[i]) return false;
        }
        return true;
    }
}
