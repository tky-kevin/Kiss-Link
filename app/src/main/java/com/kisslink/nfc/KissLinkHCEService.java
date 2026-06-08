package com.kisslink.nfc;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.util.Log;

import com.kisslink.model.BusinessCard;
import com.kisslink.model.GroupCredential;

public class KissLinkHCEService extends HostApduService {

    private static final String TAG = "KissLinkHCEService";

    private static volatile byte[] pendingCredentialBytes = null;

    // 追蹤是否送出了 APDU 回應（用於區分「真的讀到了」vs「靠近但未讀」）
    private volatile boolean apduResponseSent = false;

    // 名片送達回調（由 CardOverlayFragment 設定，主執行緒呼叫）
    private static volatile Runnable onCardDeliveredCallback = null;

    public static void setOnCardDeliveredCallback(Runnable callback) {
        onCardDeliveredCallback = callback;
    }

    public static void clearOnCardDeliveredCallback() {
        onCardDeliveredCallback = null;
    }

    // ══════════════════════════════════════════════════════════
    //  靜態 API
    // ══════════════════════════════════════════════════════════

    public static void setCredential(GroupCredential credential) {
        pendingCredentialBytes = NFCCredential.toBytes(credential);
        Log.d(TAG, "Credential set: " + credential.getSsid()
                + " (" + pendingCredentialBytes.length + " bytes)");
    }

    public static void setBusinessCard(BusinessCard card) {
        pendingCredentialBytes = NFCCredential.toCardBytes(card);
        Log.d(TAG, "Business card set (" + pendingCredentialBytes.length + " bytes)");
    }

    public static void clearCredential() {
        pendingCredentialBytes = null;
        Log.d(TAG, "Credential cleared");
    }

    // ══════════════════════════════════════════════════════════
    //  HostApduService 回調
    // ══════════════════════════════════════════════════════════

    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        if (apdu == null) return APDUHelper.SW_ERROR;

        Log.d(TAG, "APDU received: " + bytesToHex(apdu));

        if (!APDUHelper.isSelectAid(apdu)) {
            Log.w(TAG, "Unknown APDU command");
            return APDUHelper.SW_UNKNOWN_COMMAND;
        }

        byte[] payload = pendingCredentialBytes;
        if (payload == null) {
            Log.w(TAG, "Credential not ready");
            return APDUHelper.SW_CONDITIONS_NOT_MET;
        }

        byte[] response = APDUHelper.buildOkResponse(payload);
        Log.i(TAG, "Responding with payload (" + payload.length + " bytes)");
        apduResponseSent = true; // 標記 payload 已送出
        return response;
    }

    @Override
    public void onDeactivated(int reason) {
        String reasonStr = (reason == DEACTIVATION_LINK_LOSS) ? "LINK_LOSS" : "DESELECTED";
        Log.d(TAG, "HCE deactivated: " + reasonStr);
        if (apduResponseSent) {
            apduResponseSent = false;
            Runnable cb = onCardDeliveredCallback;
            onCardDeliveredCallback = null; // 防止重複觸發
            if (cb != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(cb);
            }
        }
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
