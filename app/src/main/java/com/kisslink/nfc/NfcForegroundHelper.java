package com.kisslink.nfc;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.tech.Ndef;
import android.os.Build;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kisslink.pairing.BootstrapCodec;
import com.kisslink.pairing.LocalPairing;
import com.kisslink.pairing.PairingToken;

/**
 * 在任何 Activity 啟用 NFC 前景攔截，防止系統 dispatch 跳出選擇器或導向 Google Play。
 *
 * <p>使用方式：
 * <pre>
 *   private NfcForegroundHelper nfcHelper;
 *   onCreate:  nfcHelper = new NfcForegroundHelper(this, token -> { ... });
 *   onResume:  nfcHelper.onResume();
 *   onPause:   nfcHelper.onPause();
 *   onNewIntent: nfcHelper.handleIntent(intent);
 * </pre>
 */
public class NfcForegroundHelper {

    private static final String TAG = "NfcForegroundHelper";

    public interface Callback {
        /** 碰觸到對方，解析出 token。在主執行緒呼叫。 */
        @MainThread void onPeerToken(@NonNull PairingToken peer);
        /** 自己被當作標籤讀取。在主執行緒呼叫。 */
        @MainThread void onTagRead();
    }

    private final Activity activity;
    @Nullable private final NfcAdapter nfcAdapter;
    @Nullable private final Callback callback;
    private boolean latched = false;

    public NfcForegroundHelper(@NonNull Activity activity, @Nullable Callback callback) {
        this.activity = activity;
        this.nfcAdapter = NfcAdapter.getDefaultAdapter(activity);
        this.callback = callback;
    }

    @MainThread
    public void onResume() {
        if (nfcAdapter == null || !nfcAdapter.isEnabled()) return;

        latched = false; // 回到前景 → 重新待命

        // 0. 設定本機 HCE 對外廣播的 token(與 Coordinator 同源)——
        //    讓「任何前景畫面」都是有效的 KissLink 標籤,對方才讀得到(否則一直震動)。
        try {
            KissLinkHCEService.setActiveToken(LocalPairing.current(), activity.getPackageName());
        } catch (Exception e) {
            Log.w(TAG, "setActiveToken failed: " + e.getMessage());
        }

        // 1. 前景派發：攔下所有 NFC tag，不讓系統 dispatch
        int piFlags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ? PendingIntent.FLAG_MUTABLE : 0;
        Intent dispatch = new Intent(activity, activity.getClass())
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(activity, 0, dispatch, piFlags);
        try {
            nfcAdapter.enableForegroundDispatch(activity, pi, null, null);
        } catch (Exception e) {
            Log.w(TAG, "enableForegroundDispatch failed: " + e.getMessage());
        }

        // 2. 設定 preferred HCE service，避免 Samsung ConflictResolver
        try {
            CardEmulation ce = CardEmulation.getInstance(nfcAdapter);
            if (ce != null) {
                ce.setPreferredService(activity,
                        new ComponentName(activity, KissLinkHCEService.class));
            }
        } catch (Exception e) {
            Log.w(TAG, "setPreferredService failed: " + e.getMessage());
        }

        // 3. 監聽自己被讀取
        KissLinkHCEService.setOnTagReadListener(() ->
                activity.runOnUiThread(this::fireTagRead));
    }

    @MainThread
    private void fireTagRead() {
        if (latched || callback == null) return;
        latched = true;
        callback.onTagRead();
    }

    @MainThread
    public void onPause() {
        KissLinkHCEService.setOnTagReadListener(null);
        if (nfcAdapter == null) return;
        try {
            CardEmulation ce = CardEmulation.getInstance(nfcAdapter);
            if (ce != null) ce.unsetPreferredService(activity);
        } catch (Exception e) {
            Log.w(TAG, "unsetPreferredService failed: " + e.getMessage());
        }
        try {
            nfcAdapter.disableForegroundDispatch(activity);
        } catch (Exception e) {
            Log.w(TAG, "disableForegroundDispatch failed: " + e.getMessage());
        }
    }

    /**
     * 處理 NFC intent，嘗試解析出 PairingToken。
     * @return true if a token was found and delivered to callback
     */
    @MainThread
    public boolean handleIntent(@Nullable Intent intent) {
        if (intent == null || callback == null || latched) return false;
        String action = intent.getAction();
        if (!NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)) {
            return false;
        }

        // 嘗試從系統預讀的 NDEF 解析 token
        Parcelable[] raw = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
        if (raw != null && raw.length > 0 && raw[0] instanceof NdefMessage) {
            PairingToken t = BootstrapCodec.parseNdef((NdefMessage) raw[0]);
            if (t != null) {
                latched = true;
                callback.onPeerToken(t);
                return true;
            }
        }

        // 否則自己連上標籤讀
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag != null) {
            readTagAsync(tag);
            return true;
        }
        return false;
    }

    private void readTagAsync(@NonNull Tag tag) {
        new Thread(() -> {
            Ndef ndef = Ndef.get(tag);
            PairingToken t = null;
            if (ndef != null) {
                try {
                    ndef.connect();
                    t = BootstrapCodec.parseNdef(ndef.getNdefMessage());
                } catch (Exception e) {
                    Log.w(TAG, "readTagAsync failed: " + e.getMessage());
                } finally {
                    try { ndef.close(); } catch (Exception ignored) {}
                }
            }
            if (t != null && callback != null) {
                final PairingToken peer = t;
                activity.runOnUiThread(() -> {
                    if (latched) return;
                    latched = true;
                    callback.onPeerToken(peer);
                });
            }
        }, "nfc-fg-read").start();
    }
}
