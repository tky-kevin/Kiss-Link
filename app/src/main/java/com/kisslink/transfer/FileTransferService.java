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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.kisslink.R;
import com.kisslink.model.GroupCredential;
import com.kisslink.ui.transfer.TransferActivity;

import java.util.List;

/**
 * 檔案傳輸前景 Service。
 *
 * <h3>關鍵設計：服務層 MutableLiveData（永遠非 null）</h3>
 * <p>{@code serviceLd} 在物件建立時即初始化（{@link TransferProgress#waiting()}），
 * Binder 永遠回傳此 LiveData 而非 server/client 的 LiveData。
 * 這避免了 {@code onStartCommand()} 尚未執行時 Activity 綁定拿到 null 的問題。
 *
 * <p>server/client 的進度透過 {@code observeForever} 橋接到 {@code serviceLd}。
 */
public class FileTransferService extends Service {

    private static final String TAG = "FileTransferService";

    // ── Intent Keys ────────────────────────────────────────────
    private static final String EXTRA_ROLE       = "role";
    private static final String EXTRA_GO_IP      = "go_ip";
    private static final String EXTRA_PORT       = "port";
    private static final String EXTRA_SSID       = "ssid";
    private static final String EXTRA_PASSPHRASE = "passphrase";

    public static final String ROLE_SENDER   = "SENDER";
    public static final String ROLE_RECEIVER = "RECEIVER";

    // ── Notification ───────────────────────────────────────────
    private static final String CHANNEL_ID = "kisslink_transfer";
    private static final int    NOTIF_ID   = 1001;

    // ── 核心物件 ───────────────────────────────────────────────
    private TransferServer transferServer;
    private TransferClient transferClient;
    private String         role;

    /**
     * 服務層統一進度 LiveData —— 永遠非 null。
     * Activity 透過 Binder 觀察此物件；server/client 的進度橋接至此。
     */
    private final MutableLiveData<TransferProgress> serviceLd =
            new MutableLiveData<>(TransferProgress.waiting());

    private final TransferBinder binder = new TransferBinder();

    // ══════════════════════════════════════════════════════════
    //  Intent Factory
    // ══════════════════════════════════════════════════════════

    public static Intent senderIntent(Context ctx, GroupCredential cred) {
        Intent i = new Intent(ctx, FileTransferService.class);
        i.putExtra(EXTRA_ROLE,  ROLE_SENDER);
        i.putExtra(EXTRA_GO_IP, cred.getGoIpAddress());
        i.putExtra(EXTRA_PORT,  cred.getTransferPort());
        return i;
    }

    public static Intent receiverIntent(Context ctx, GroupCredential cred) {
        Intent i = new Intent(ctx, FileTransferService.class);
        i.putExtra(EXTRA_ROLE,       ROLE_RECEIVER);
        i.putExtra(EXTRA_GO_IP,      cred.getGoIpAddress());
        i.putExtra(EXTRA_PORT,       cred.getTransferPort());
        i.putExtra(EXTRA_SSID,       cred.getSsid());
        i.putExtra(EXTRA_PASSPHRASE, cred.getPassphrase());
        return i;
    }

    // ══════════════════════════════════════════════════════════
    //  Binder
    // ══════════════════════════════════════════════════════════

    public class TransferBinder extends Binder {

        /**
         * 傳輸進度 LiveData（永遠非 null）。
         * 回傳服務層 {@code serviceLd}，在 {@code onStartCommand()} 執行前也安全可用。
         */
        public LiveData<TransferProgress> getProgress() {
            return serviceLd;
        }

        /** 傳送方：加入待傳檔案並開始傳送。 */
        public void sendFiles(List<Uri> uris) {
            if (transferServer != null) {
                transferServer.enqueue(uris);
                transferServer.startSending();
            } else {
                Log.w(TAG, "sendFiles: transferServer is null (service not ready)");
            }
        }

        /** 取消傳輸。 */
        public void cancel() {
            if (transferServer != null) transferServer.cancel();
            if (transferClient != null) transferClient.cancel();
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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }

        role = intent.getStringExtra(EXTRA_ROLE);
        String goIp = intent.getStringExtra(EXTRA_GO_IP);
        int    port = intent.getIntExtra(EXTRA_PORT, 47890);

        startForeground(NOTIF_ID, buildNotification("準備中…", 0));

        GroupCredential cred = new GroupCredential(
                intent.getStringExtra(EXTRA_SSID),
                intent.getStringExtra(EXTRA_PASSPHRASE),
                goIp, port);

        if (ROLE_SENDER.equals(role)) {
            transferServer = new TransferServer(this);
            transferServer.startListening();
            // 橋接 server 進度 → 服務層 LiveData（observeForever 需在主執行緒）
            bridgeToServiceLd(transferServer.getProgress());
        } else {
            transferClient = new TransferClient(this, cred);
            transferClient.connect();
            bridgeToServiceLd(transferClient.getProgress());
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (transferServer != null) transferServer.release();
        if (transferClient != null) transferClient.release();
        Log.d(TAG, "FileTransferService destroyed");
    }

    // ══════════════════════════════════════════════════════════
    //  進度橋接
    // ══════════════════════════════════════════════════════════

    /**
     * 將 server/client 的進度 LiveData 橋接到服務層 {@code serviceLd}，
     * 並同步更新通知列。
     *
     * <p>必須在主執行緒呼叫 {@code observeForever}，{@code onStartCommand()} 已在主執行緒。
     */
    private void bridgeToServiceLd(LiveData<TransferProgress> src) {
        src.observeForever(progress -> {
            if (progress == null) return;
            serviceLd.postValue(progress);   // 廣播給 TransferActivity

            // 同步更新前景通知
            switch (progress.phase) {
                case TRANSFERRING:
                    updateNotification(progress.fileName, progress.percentInt());
                    break;
                case ALL_DONE:
                    updateNotification("傳輸完成", 100);
                    stopSelf();
                    break;
                case CANCELLED:
                case ERROR:
                    updateNotification(
                            progress.phase == TransferProgress.Phase.ERROR
                                    ? "傳輸失敗" : "已取消", 0);
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
