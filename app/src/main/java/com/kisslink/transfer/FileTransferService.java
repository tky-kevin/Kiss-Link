package com.kisslink.transfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import com.kisslink.data.db.TransferRecordEntity;
import com.kisslink.data.repository.TransferRepository;
import com.kisslink.model.GroupCredential;
import com.kisslink.nfc.KissLinkHCEService;
import com.kisslink.ui.transfer.TransferActivity;
import com.kisslink.wifidirect.ConnectionState;
import com.kisslink.wifidirect.WifiDirectManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 檔案傳輸前景 Service —— 整個傳輸 session 的「單一擁有者」。
 *
 * <h3>為何由 Service 擁有 Wi-Fi Direct 連線？</h3>
 * <p>連線（{@link WifiDirectManager}）的生命週期必須橫跨「配對 → 傳輸」兩個畫面。
 * 過去它被綁在短命的 PairingActivity/PairingViewModel 上，導致 Activity 結束時
 * {@code reset()} 在交接瞬間拆掉網路綁定。改由長命的 Service 持有後，連線從配對一路
 * 存活到傳輸結束，PairingActivity / TransferActivity 都只是「綁定並觀察」的薄客戶端。
 *
 * <h3>角色流程</h3>
 * <pre>
 *  SENDER:   onStartCommand(SENDER) → createGroupAsGO()
 *              → credential 就緒 → HCE 注入 + TransferServer.startListening()（立即 listen）
 *              → 對方連入 → state=CONNECTED；TransferActivity 在握手後自動送檔
 *  RECEIVER: onStartCommand(RECEIVER) → 等 PairingActivity 由 NFC 取得憑證後呼叫
 *              submitReceiverCredential() → connectAsClient()
 *              → state=CONNECTED → TransferClient.connect() 開始接收
 * </pre>
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class FileTransferService extends Service {

    private static final String TAG = "FileTransferService";

    // ── Intent Keys ────────────────────────────────────────────
    private static final String EXTRA_ROLE = "role";
    public static final String ROLE_SENDER   = "SENDER";
    public static final String ROLE_RECEIVER = "RECEIVER";

    // ── Notification ───────────────────────────────────────────
    private static final String CHANNEL_ID = "kisslink_transfer";
    private static final int    NOTIF_ID   = 1001;

    // ── Session 核心物件 ───────────────────────────────────────
    private WifiDirectManager wifiManager;
    private TransferServer    transferServer;
    private TransferClient    transferClient;
    private String            role;
    @Nullable private GroupCredential credential;

    private boolean serverListening = false;
    private boolean clientStarted   = false;

    // ── 傳送方送檔的去 UI 耦合狀態 ─────────────────────────────
    private boolean haveFiles   = false; // 已有待傳檔案入列
    private boolean serverReady = false; // server 已與接收方握手完成
    private boolean sendStarted = false; // 已開始送檔（避免重複）

    // ── 接收方：本次 session 收到的檔案 URI 列表 ─────────────────
    private final List<String> receivedFileUris = new ArrayList<>();

    /** 傳輸層進度匯總（內部用）—— server/client 的進度都橋接到此。 */
    private final MutableLiveData<TransferProgress> serviceLd =
            new MutableLiveData<>(TransferProgress.waiting());

    /** 對 UI 公開的「單一狀態」—— 由連線狀態 + 連線錯誤 + 傳輸進度三者合併而成。 */
    private final MediatorLiveData<SessionState> sessionLd = new MediatorLiveData<>();

    // recomputeSession 用的最新來源值
    private ConnectionState lastConn      = ConnectionState.IDLE;
    @Nullable private String          lastConnError = null;
    @Nullable private TransferProgress lastTransfer = null;

    private final TransferBinder binder = new TransferBinder();

    // ══════════════════════════════════════════════════════════
    //  Intent Factory（只帶角色；憑證改由連線流程內部產生/傳入）
    // ══════════════════════════════════════════════════════════

    public static Intent senderIntent(Context ctx) {
        return new Intent(ctx, FileTransferService.class).putExtra(EXTRA_ROLE, ROLE_SENDER);
    }

    public static Intent receiverIntent(Context ctx) {
        return new Intent(ctx, FileTransferService.class).putExtra(EXTRA_ROLE, ROLE_RECEIVER);
    }

    // ══════════════════════════════════════════════════════════
    //  Binder
    // ══════════════════════════════════════════════════════════

    public class TransferBinder extends Binder {

        /** 單一 session 狀態（連線 + 傳輸 + 錯誤合一）—— UI 唯一需要觀察的對象。 */
        public LiveData<SessionState> getSessionState() { return sessionLd; }

        @Nullable public GroupCredential getCredential() { return credential; }

        public String getRole() { return role; }

        /** 接收方：PairingActivity 由 NFC 取得憑證後呼叫，觸發 silent P2P 連線。 */
        public void submitReceiverCredential(GroupCredential cred) {
            credential = cred;
            wifiManager.connectAsClient(cred);
        }

        /**
         * 傳送方：加入待傳檔案。實際開送由 Service 在「server 已握手 且 檔案已入列」時
         * 自動觸發（{@link #maybeStartSending()}），不依賴 UI 導航時機。
         */
        public void enqueueFiles(List<Uri> uris) {
            // 檔案集每場（每個 Service 實例）只接受一次：避免 PairingActivity 重建/多次綁定
            // 重複 enqueue 造成 fileQueue 累加、同一批檔案被送多輪。
            if (sendStarted || haveFiles) return;
            if (transferServer != null && uris != null && !uris.isEmpty()) {
                transferServer.enqueue(uris);
                haveFiles = true;
                maybeStartSending();
            } else if (transferServer == null) {
                Log.w(TAG, "enqueueFiles: transferServer is null (not a sender / not ready)");
            }
        }

        /** 相容舊呼叫端；語義同 {@link #enqueueFiles(List)}。 */
        public void sendFiles(List<Uri> uris) { enqueueFiles(uris); }

        /** 取消傳輸。 */
        public void cancel() {
            if (transferServer != null) transferServer.cancel();
            if (transferClient != null) transferClient.cancel();
        }

        /** 接收方：取得本次傳輸收到的所有檔案 URI（ALL_DONE 後使用）*/
        public List<String> getReceivedFileUris() {
            return new ArrayList<>(receivedFileUris);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    // ══════════════════════════════════════════════════════════
    //  生命週期
    // ══════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        // 連線層在 Service 一建立就準備好，並由 Service 註冊 Wi-Fi Direct 廣播（橫跨整個 session）。
        wifiManager = new WifiDirectManager(this);
        wifiManager.registerReceiver(this);
        wireWifiObservers();

        // 合併三個來源 → 單一 SessionState（UI 唯一觀察點）。
        sessionLd.addSource(wifiManager.getState(), cs -> { lastConn = cs; recomputeSession(); });
        sessionLd.addSource(wifiManager.getError(), e -> { lastConnError = e; recomputeSession(); });
        sessionLd.addSource(serviceLd,             tp -> { lastTransfer = tp; recomputeSession(); });
        recomputeSession();
    }

    /**
     * 計算當前 SessionState：傳輸層事件優先（一旦進入傳輸階段就以它為準），
     * 其次是連線錯誤，最後才是連線階段。
     */
    private void recomputeSession() {
        if (lastTransfer != null && lastTransfer.phase != TransferProgress.Phase.WAITING) {
            sessionLd.setValue(SessionState.fromTransfer(lastTransfer));
        } else if (lastConnError != null && !lastConnError.isEmpty()) {
            sessionLd.setValue(SessionState.error(lastConnError));
        } else {
            sessionLd.setValue(SessionState.fromConnection(lastConn));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }

        if (role == null) {
            role = intent.getStringExtra(EXTRA_ROLE);
            startForeground(NOTIF_ID, buildNotification("準備中…", 0));

            if (ROLE_SENDER.equals(role)) {
                transferServer = new TransferServer(this);
                transferServer.setEventListener(this::onFileCompleted);
                bridgeToServiceLd(transferServer.getProgress());
                wifiManager.createGroupAsGO(); // credential 就緒後 → HCE + 立即 listen（見 wireWifiObservers）
            }
            // RECEIVER：等待 PairingActivity 的 submitReceiverCredential()
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (transferServer != null) transferServer.release();
        if (transferClient != null) transferClient.release();
        if (wifiManager != null) {
            wifiManager.unregisterReceiver(this);
            wifiManager.reset(); // 真正的 session 結束點才拆連線（不再踩配對→傳輸的交接）
        }
        KissLinkHCEService.clearCredential();
        Log.d(TAG, "FileTransferService destroyed");
    }

    // ══════════════════════════════════════════════════════════
    //  Wi-Fi Direct 觀察者（連線層 → 傳輸層的銜接）
    // ══════════════════════════════════════════════════════════

    private void wireWifiObservers() {
        // 憑證就緒（GO 端群組已形成）：注入 HCE，並立即開始 listen。
        wifiManager.getCredential().observeForever(cred -> {
            if (cred == null) return;
            credential = cred;
            if (ROLE_SENDER.equals(role)) {
                KissLinkHCEService.setCredential(cred);
                if (transferServer != null && !serverListening) {
                    serverListening = true;
                    transferServer.startListening(); // 群組一形成就 listen → 消除 ECONNREFUSED 競態
                    Log.i(TAG, "Sender: server listening early (group hosting)");
                }
            }
        });

        // 連線成功：接收方此時才開始 TCP 接收。
        wifiManager.getState().observeForever(state -> {
            if (state == ConnectionState.CONNECTED
                    && ROLE_RECEIVER.equals(role)
                    && !clientStarted
                    && credential != null) {
                clientStarted = true;
                transferClient = new TransferClient(this, credential);
                transferClient.setEventListener(this::onFileCompleted);
                bridgeToServiceLd(transferClient.getProgress());
                transferClient.connect();
                Log.i(TAG, "Receiver: P2P connected → starting TransferClient");
            }
        });
    }

    /**
     * 傳送方：當「接收方已握手(serverReady)」且「檔案已入列(haveFiles)」兩個條件都滿足時開送。
     * 兩個事件誰先誰後都行——後到的那一個負責觸發，徹底擺脫對 UI 導航時機的依賴。
     */
    private void maybeStartSending() {
        if (ROLE_SENDER.equals(role) && serverReady && haveFiles && !sendStarted) {
            sendStarted = true;
            transferServer.startSending();
            Log.i(TAG, "Sender: handshaked + files queued → start sending");
        }
    }

    /**
     * 逐檔完成回呼（由 TransferServer/TransferClient 在背景執行緒同步呼叫）——
     * 一檔一次、不經會合併的 LiveData，因此歷史紀錄數量永遠正確。
     */
    private void onFileCompleted(String fileName, long sizeBytes, long avgSpeedBps,
                                 boolean success, @Nullable String fileUri) {
        if (role == null) return;
        String direction = ROLE_SENDER.equals(role) ? "SEND" : "RECEIVE";
        TransferRepository repo = TransferRepository.getInstance(this);
        TransferRecordEntity record = repo.buildRecord(
                direction, fileName, sizeBytes, success, avgSpeedBps, fileUri);
        repo.insert(record);

        // 接收方：收集 URI 供預覽使用
        if (ROLE_RECEIVER.equals(role) && fileUri != null && success) {
            receivedFileUris.add(fileUri);
        }
    }

    // ══════════════════════════════════════════════════════════
    //  進度橋接
    // ══════════════════════════════════════════════════════════

    private void bridgeToServiceLd(LiveData<TransferProgress> src) {
        src.observeForever(progress -> {
            if (progress == null) return;
            serviceLd.postValue(progress);

            switch (progress.phase) {
                case CONNECTED:
                    // 傳送方：server 與接收方握手完成 → 標記就緒，必要時自動開送。
                    if (ROLE_SENDER.equals(role)) {
                        serverReady = true;
                        maybeStartSending();
                    }
                    break;
                case TRANSFERRING:
                    updateNotification(progress.fileName, progress.percentInt());
                    break;
                case ALL_DONE:
                    updateNotification("傳輸完成", 100);
                    stopSelf();
                    break;
                case CANCELLED:
                    updateNotification("已取消", 0);
                    stopSelf();
                    break;
                case ERROR:
                    updateNotification("傳輸失敗", 0);
                    stopSelf();
                    break;
                default:
                    break;
            }
        });
    }

    // ══════════════════════════════════════════════════════════
    //  通知
    // ══════════════════════════════════════════════════════════

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "KissLink 傳輸", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("顯示檔案傳輸進度");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification(String text, int progress) {
        Intent tap = new Intent(this, TransferActivity.class);
        tap.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, tap, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("KissLink 傳輸")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setSilent(true);

        if (progress > 0 && progress < 100) {
            b.setProgress(100, progress, false);
        }
        return b.build();
    }

    private void updateNotification(String text, int progress) {
        getSystemService(NotificationManager.class)
                .notify(NOTIF_ID, buildNotification(text, progress));
    }
}
