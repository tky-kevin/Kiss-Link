package com.kisslink.transfer;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.kisslink.model.GroupCredential;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 接收方（Client 端）的 TCP 客戶端。
 *
 * <h3>職責</h3>
 * <ol>
 *   <li>連線至 GO 的 {@code 192.168.49.1:47890}。</li>
 *   <li>完成握手後持續等待並接收檔案。</li>
 *   <li>使用 MediaStore API 將檔案寫入 Downloads（Android 10+）。</li>
 *   <li>透過 LiveData 回報進度與錯誤。</li>
 * </ol>
 */
public class TransferClient {

    private static final String TAG = "TransferClient";

    private final Context context;
    private final String  goIp;
    private final int     port;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Socket           socket;
    private DataOutputStream out;
    private DataInputStream  in;
    private volatile boolean cancelled = false;

    private volatile TransferEventListener eventListener;
    // 進行中的檔案（供失敗時回報）
    private String curName; private long curSize;

    private String peerName = "";
    public String getPeerName() { return peerName; }

    /** 設定逐檔完成回呼（用於可靠的歷史紀錄，不經會合併的 LiveData）。 */
    public void setEventListener(TransferEventListener l) { this.eventListener = l; }

    private void fireFileCompleted(String name, long size, long speed, boolean success) {
        TransferEventListener l = eventListener;
        if (l != null && name != null) l.onFileCompleted(name, size, speed, success);
    }

    private final MutableLiveData<TransferProgress> progressLd =
            new MutableLiveData<>(TransferProgress.waiting());

    // ══════════════════════════════════════════════════════════
    //  建構子
    // ══════════════════════════════════════════════════════════

    public TransferClient(Context context, GroupCredential credential) {
        this.context = context.getApplicationContext();
        this.goIp    = credential.getGoIpAddress();
        this.port    = credential.getTransferPort();
    }

    // ══════════════════════════════════════════════════════════
    //  公開 API
    // ══════════════════════════════════════════════════════════

    /**
     * 連線至 GO 並開始接收。在背景執行緒執行。
     * 通常在 Wi-Fi Direct 連線成功（{@link com.kisslink.wifidirect.ConnectionState#CONNECTED}）後呼叫。
     */
    public void connect() {
        executor.execute(this::doConnectAndReceive);
    }

    /** 取消接收並關閉連線。 */
    public void cancel() {
        cancelled = true;
        try { sendPacket(TransferProtocol.makeCancel(), null); } catch (Exception ignored) {}
        close();
        postProgress(TransferProgress.cancelled());
    }

    public LiveData<TransferProgress> getProgress() { return progressLd; }

    // ══════════════════════════════════════════════════════════
    //  連線與握手
    // ══════════════════════════════════════════════════════════

    /** 單次 connect 的逾時（主機不可達時防止永久阻塞）。 */
    private static final int CONNECT_TIMEOUT_MS = 2000;
    /**
     * 連線重試的「時間視窗」。接收方常比傳送方早就緒，此時 TCP 會立刻回 ECONNREFUSED
     * （主機通、但 server 尚未 listen）。ECONNREFUSED 是廉價的即時失敗，因此用一個較長的
     * 時間窗持續重試，等待傳送方的 TransferServer 完成 bind/accept。
     */
    private static final long CONNECT_RETRY_WINDOW_MS   = 20_000;
    private static final long CONNECT_RETRY_INTERVAL_MS = 700;

    private void doConnectAndReceive() {
        // ── 階段一：建立連線 + 握手（server 可能尚未 listen，持續重試到 deadline）──
        long deadline = System.currentTimeMillis() + CONNECT_RETRY_WINDOW_MS;
        int attempt = 0;
        while (!cancelled) {
            attempt++;
            try {
                Log.i(TAG, "Connecting to " + goIp + ":" + port + " (attempt " + attempt + ")");
                socket = new Socket();
                socket.connect(new java.net.InetSocketAddress(goIp, port), CONNECT_TIMEOUT_MS);

                out = new DataOutputStream(socket.getOutputStream());
                in  = new DataInputStream(socket.getInputStream());

                // 取自己的名字
                String myName = "";
                try {
                    com.kisslink.data.repository.UserProfileRepository repo =
                        com.kisslink.data.repository.UserProfileRepository.getInstance(context);
                    com.kisslink.model.UserProfile profile = repo.getUserProfile();
                    if (profile != null && profile.getName() != null) myName = profile.getName();
                } catch (Exception ignored) {}
                byte[] myNameBytes = myName.getBytes(java.nio.charset.StandardCharsets.UTF_8);

                // 發送 HANDSHAKE 帶上名字
                sendPacket(TransferProtocol.makeHandshake(myNameBytes.length), myNameBytes);

                // 讀取 HANDSHAKE_ACK（含對方名字）
                TransferProtocol.Header ack = readHeader();
                if (ack.type != TransferProtocol.TYPE_HANDSHAKE_ACK)
                    throw new TransferProtocol.InvalidPacketException("Expected HANDSHAKE_ACK");

                int ackNameLen = ack.metaLen & 0xFFFF;
                if (ackNameLen > 0) {
                    byte[] nameBytes = readBytes(ackNameLen);
                    peerName = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);
                }
                Log.i(TAG, "Handshake complete, peerName=" + peerName);

                break; // 握手成功 → 離開連線重試迴圈

            } catch (IOException | TransferProtocol.InvalidPacketException e) {
                close(); // 關閉本次失敗的 socket，下一輪重新建立
                if (cancelled) return;
                if (System.currentTimeMillis() >= deadline) {
                    Log.e(TAG, "Connect failed after retry window", e);
                    postProgress(TransferProgress.error("無法連線到傳送方，請重試（" + e.getMessage() + "）"));
                    return;
                }
                Log.w(TAG, "Connect failed, retrying... (" + e.getMessage() + ")");
                try { Thread.sleep(CONNECT_RETRY_INTERVAL_MS); }
                catch (InterruptedException ignored) { return; }
            }
        }
        if (cancelled) { close(); return; }

        // ── 階段二：接收檔案（握手已成功，連線單次有效，不重連）──
        Log.d(TAG, "Handshake complete, waiting for files...");
        postProgress(TransferProgress.connected());
        try {
            receiveAllFiles();
        } catch (IOException | TransferProtocol.InvalidPacketException e) {
            if (!cancelled) {
                Log.e(TAG, "Receive error", e);
                fireFileCompleted(curName, curSize, 0, false); // 進行中的檔案記為失敗
                postProgress(TransferProgress.error("接收失敗：" + e.getMessage()));
            }
        } finally {
            close();
        }
    }

    // ══════════════════════════════════════════════════════════
    //  檔案接收
    // ══════════════════════════════════════════════════════════

    private void receiveAllFiles() throws IOException, TransferProtocol.InvalidPacketException {
        while (!cancelled) {
            // 等待下一個 header（FILE_META 或 CANCEL）
            TransferProtocol.Header h = readHeader();

            if (h.type == TransferProtocol.TYPE_CANCEL) {
                Log.i(TAG, "Sender cancelled");
                postProgress(TransferProgress.cancelled());
                return;
            }
            if (h.type != TransferProtocol.TYPE_FILE_META)
                throw new TransferProtocol.InvalidPacketException("Expected FILE_META, got " + h.type);

            receiveOneFile(h);

            // 若這是最後一個檔案（fileId == fileCount-1），結束
            if (h.fileId >= h.fileCount - 1) {
                Log.i(TAG, "All " + h.fileCount + " file(s) received");
                postProgress(TransferProgress.allDone(h.fileCount));
                return;
            }
        }
    }

    private void receiveOneFile(TransferProtocol.Header meta)
            throws IOException, TransferProtocol.InvalidPacketException {

        // 1. 讀取 FILE_META JSON
        byte[] metaBytes = readBytes(meta.metaLen & 0xFFFF);
        JSONObject json;
        String fileName;
        long   totalSize;
        String mime;
        try {
            json = new JSONObject(new String(metaBytes, StandardCharsets.UTF_8));
            fileName  = json.getString("name");
            totalSize = json.getLong("size");
            mime      = json.optString("mime", "application/octet-stream");
        } catch (Exception e) { throw new IOException("JSON parse error", e); }

        curName = fileName; curSize = totalSize; // 標記進行中（供失敗回報）
        Log.d(TAG, "Receiving: " + fileName + " (" + totalSize + " bytes)");

        // 2. 回覆 READY_ACK
        sendPacket(TransferProtocol.makeReadyAck(meta.fileId), null);

        // 3. 建立輸出檔案（MediaStore Downloads，Android 10+）
        Uri outputUri = createOutputUri(fileName, mime);
        ContentResolver cr = context.getContentResolver();
        long doneBytes = 0;
        long startMs = System.currentTimeMillis();

        try (OutputStream fos = cr.openOutputStream(outputUri)) {
            if (fos == null) throw new IOException("Cannot create output file: " + fileName);

            while (!cancelled) {
                TransferProtocol.Header chunk = readHeader();

                if (chunk.type == TransferProtocol.TYPE_COMPLETE) {
                    // 傳送方通知完成
                    sendPacket(TransferProtocol.makeCompleteAck(meta.fileId), null);
                    break;
                }
                if (chunk.type == TransferProtocol.TYPE_CANCEL) {
                    postProgress(TransferProgress.cancelled());
                    return;
                }
                if (chunk.type != TransferProtocol.TYPE_DATA_CHUNK)
                    throw new TransferProtocol.InvalidPacketException("Expected DATA_CHUNK");

                byte[] data = readBytes(chunk.chunkLen);

                // CRC 驗證
                int actualCrc = TransferProtocol.crc32(data, 0, data.length);
                if (actualCrc != chunk.crc32)
                    throw new TransferProtocol.InvalidPacketException(
                            "CRC mismatch at offset " + chunk.offset);

                fos.write(data);
                doneBytes += data.length;

                long elapsedMs = System.currentTimeMillis() - startMs;
                long speed = elapsedMs > 0 ? doneBytes * 1000 / elapsedMs : 0;
                postProgress(new TransferProgress.Builder()
                        .phase(TransferProgress.Phase.TRANSFERRING)
                        .fileName(fileName).totalBytes(totalSize).doneBytes(doneBytes)
                        .speedBps(speed).fileIndex(meta.fileId).fileCount(meta.fileCount)
                        .build());
            }
        }

        // 全部資料寫入完成後才清除 IS_PENDING，避免其他 app 讀到不完整的檔案
        finalizePendingUri(cr, outputUri);

        long elapsed = System.currentTimeMillis() - startMs;
        long avgSpeed = elapsed > 0 ? doneBytes * 1000 / elapsed : 0;
        fireFileCompleted(fileName, totalSize, avgSpeed, true); // 可靠的逐檔成功回報
        curName = null;

        postProgress(new TransferProgress.Builder()
                .phase(TransferProgress.Phase.FILE_DONE)
                .fileName(fileName).totalBytes(totalSize).doneBytes(totalSize)
                .fileIndex(meta.fileId).fileCount(meta.fileCount)
                .build());
        Log.i(TAG, "File received: " + fileName);
    }

    // ══════════════════════════════════════════════════════════
    //  MediaStore 輸出（Android 10+）
    // ══════════════════════════════════════════════════════════

    private Uri createOutputUri(String fileName, String mime) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, mime);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        // 子目錄：Downloads/KissLink/
        values.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/KissLink");

        ContentResolver cr = context.getContentResolver();
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri = cr.insert(collection, values);
        if (uri == null) throw new IOException("MediaStore insert failed for: " + fileName);
        return uri;
    }

    private void finalizePendingUri(ContentResolver cr, Uri uri) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        cr.update(uri, values, null, null);
    }

    // ══════════════════════════════════════════════════════════
    //  I/O 工具
    // ══════════════════════════════════════════════════════════

    private void sendPacket(TransferProtocol.Header h, byte[] payload) throws IOException {
        out.write(TransferProtocol.encodeHeader(h));
        if (payload != null && payload.length > 0) out.write(payload);
        out.flush();
    }

    private TransferProtocol.Header readHeader()
            throws IOException, TransferProtocol.InvalidPacketException {
        return TransferProtocol.decodeHeader(readBytes(TransferProtocol.HEADER_SIZE));
    }

    private byte[] readBytes(int n) throws IOException {
        byte[] buf = new byte[n];
        in.readFully(buf);
        return buf;
    }

    private void postProgress(TransferProgress p) { progressLd.postValue(p); }

    private void close() {
        try { if (out != null) out.close(); } catch (IOException ignored) {}
        try { if (in  != null) in.close();  } catch (IOException ignored) {}
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    public void release() {
        cancelled = true;
        close();
        executor.shutdownNow();
    }
}
