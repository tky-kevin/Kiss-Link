package com.kisslink.pairing;

import android.app.Activity;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kisslink.nfc.KissLinkHCEService;

import java.util.Random;

/**
 * 方案 A 的核心:在 NFC <b>reader(輪詢)</b> 與 <b>listen(HCE 可被讀)</b> 之間
 * 以 ~120–200ms + jitter 快速切換,讓兩台都閒置在 App 的手機一貼就能「一讀一寫」對上。
 *
 * <h3>對齊後的非對稱角色(交給 BLE 階段)</h3>
 * <ul>
 *   <li>讀到對方 token 的一方 = <b>reader</b> → 之後當 BLE central。
 *       回呼 {@link Callback#onPeerToken}。</li>
 *   <li>自己 HCE 被讀的一方 = <b>tag</b> → 之後當 BLE peripheral(以自身 nonce 廣播)。
 *       回呼 {@link Callback#onTagRead}。</li>
 * </ul>
 * 同一次貼合,reader 相位的一方 HCE 必關閉、listen 相位的一方 reader 必關閉,
 * 因此「恰好一方讀、一方被讀」,角色天然互斥。第一次成功交換即 {@link #stop()}。
 *
 * <h3>生命週期</h3>
 * <pre>
 *   onResume:  setLocalToken(...); start();
 *   onPause / latch: stop();
 * </pre>
 * {@code enableReaderMode} 需前景 resumed Activity,故由前景畫面驅動。
 */
public class NfcPairingController {

    private static final String TAG = "NfcPairingController";

    private static final int PHASE_MIN_MS = 120;
    private static final int PHASE_JITTER  = 80; // 實際相位 120–199ms

    public interface Callback {
        /** reader 相位讀到對方標籤的 token(本機之後當 BLE central)。 */
        @MainThread void onPeerToken(@NonNull PairingToken peer);
        /** 自己的 HCE 被對方讀走(本機之後當 BLE peripheral)。 */
        @MainThread void onTagRead();
        /** 無法啟動(不支援 NFC / 未開啟)。 */
        @MainThread void onError(@NonNull String message);
    }

    private final Activity activity;
    private final Callback callback;
    @Nullable private final NfcAdapter nfcAdapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rng = new Random();

    private volatile boolean running = false;
    private boolean inReaderPhase = false;

    public NfcPairingController(@NonNull Activity activity, @NonNull Callback callback) {
        this.activity = activity;
        this.callback = callback;
        this.nfcAdapter = NfcAdapter.getDefaultAdapter(activity);
    }

    /** 設定本機這場要廣播的 token(寫進 HCE)。可在 start() 前或進行中更新。 */
    public void setLocalToken(@NonNull PairingToken token) {
        KissLinkHCEService.setActiveToken(token);
    }

    // ══════════════════════════════════════════════════════════
    //  啟動 / 停止
    // ══════════════════════════════════════════════════════════

    @MainThread
    public void start() {
        if (running) return;
        if (nfcAdapter == null) { callback.onError("此裝置不支援 NFC"); return; }
        if (!nfcAdapter.isEnabled()) { callback.onError("請先在設定中開啟 NFC"); return; }

        running = true;
        // 自己 HCE 被讀的訊號(NFC 執行緒)→ 切回主執行緒處理。
        KissLinkHCEService.setOnTagReadListener(() -> handler.post(this::onHceRead));

        // 從 listen 相位起手(HCE 先就緒),再開始翻面。
        inReaderPhase = false;
        handler.postDelayed(flipRunnable, nextPhaseMs());
        Log.d(TAG, "Toggling started");
    }

    @MainThread
    public void stop() {
        if (!running) return;
        running = false;
        handler.removeCallbacks(flipRunnable);
        KissLinkHCEService.setOnTagReadListener(null);
        try {
            if (nfcAdapter != null) nfcAdapter.disableReaderMode(activity);
        } catch (Exception e) {
            Log.w(TAG, "disableReaderMode on stop: " + e.getMessage());
        }
        inReaderPhase = false;
        Log.d(TAG, "Toggling stopped");
    }

    // ══════════════════════════════════════════════════════════
    //  相位切換
    // ══════════════════════════════════════════════════════════

    private final Runnable flipRunnable = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (inReaderPhase) enterListenPhase();
            else               enterReaderPhase();
            handler.postDelayed(this, nextPhaseMs());
        }
    };

    /** reader 相位:輪詢讀對方 HCE 的 NDEF。期間本機 HCE 被系統關閉(不可被讀)。 */
    private void enterReaderPhase() {
        inReaderPhase = true;
        if (nfcAdapter == null) return;
        try {
            // 不加 SKIP_NDEF_CHECK:讓系統把對方 Type-4 HCE 當成 Ndef-tech 標籤呈現。
            nfcAdapter.enableReaderMode(
                    activity,
                    readerCallback,
                    NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B,
                    null);
        } catch (Exception e) {
            Log.w(TAG, "enableReaderMode failed: " + e.getMessage());
        }
    }

    /** listen 相位:關閉 reader → 控制器回到 listen 模式,本機 HCE 可被對方讀。 */
    private void enterListenPhase() {
        inReaderPhase = false;
        if (nfcAdapter == null) return;
        try {
            nfcAdapter.disableReaderMode(activity);
        } catch (Exception e) {
            Log.w(TAG, "disableReaderMode failed: " + e.getMessage());
        }
    }

    private int nextPhaseMs() {
        return PHASE_MIN_MS + rng.nextInt(PHASE_JITTER);
    }

    // ══════════════════════════════════════════════════════════
    //  讀到對方 / 自己被讀
    // ══════════════════════════════════════════════════════════

    /** 在 NFC binder 執行緒呼叫(非主執行緒)——可做阻塞 I/O 讀 NDEF。 */
    private final NfcAdapter.ReaderCallback readerCallback = tag -> {
        PairingToken peer = readPeerToken(tag);
        if (peer != null) handler.post(() -> deliverPeer(peer));
    };

    @Nullable
    private static PairingToken readPeerToken(@NonNull Tag tag) {
        Ndef ndef = Ndef.get(tag);
        if (ndef == null) return null;
        try {
            ndef.connect();
            NdefMessage msg = ndef.getNdefMessage();
            return BootstrapCodec.parseNdef(msg);
        } catch (Exception e) {
            Log.w(TAG, "readPeerToken failed: " + e.getMessage());
            return null;
        } finally {
            try { ndef.close(); } catch (Exception ignored) {}
        }
    }

    @MainThread
    private void deliverPeer(@NonNull PairingToken peer) {
        if (!running) return;
        Log.i(TAG, "Peer token read (role=reader): " + peer);
        stop();
        callback.onPeerToken(peer);
    }

    @MainThread
    private void onHceRead() {
        if (!running) return;
        Log.i(TAG, "Own tag read by peer (role=tag)");
        stop();
        callback.onTagRead();
    }
}
