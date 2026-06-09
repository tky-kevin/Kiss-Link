package com.kisslink.transfer;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.kisslink.utils.FileUtils;
import com.kisslink.wifidirect.WifiDirectManager;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 傳送方（GO 端）的 TCP 伺服器。
 *
 * <h3>職責</h3>
 * <ol>
 *   <li>在 {@link WifiDirectManager#TRANSFER_PORT} 監聽 TCP 連線。</li>
 *   <li>等待接收方連入並完成握手。</li>
 *   <li>依序發送 {@link #enqueue} 加入的檔案（URI）。</li>
 *   <li>透過 {@link #getProgress()} LiveData 通報進度。</li>
 * </ol>
 *
 * <h3>使用方式</h3>
 * <pre>
 *   server = new TransferServer(context);
 *   server.startListening();           // 開始等待連線（背景執行緒）
 *   ...（NFC 配對完成後）
 *   server.enqueue(uriList);           // 加入要傳送的檔案
 *   server.startSending();             // 開始傳送
 * </pre>
 */
public class TransferServer {

    private static final String TAG = "TransferServer";

    private final Context context;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private ServerSocket serverSocket;
    private Socket       clientSocket;
    private DataOutputStream out;
    private DataInputStream  in;

    private volatile boolean cancelled = false;
    private final List<Uri> fileQueue = new CopyOnWriteArrayList<>();

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

    public TransferServer(Context context) {
        this.context = context.getApplicationContext();
    }

    // ══════════════════════════════════════════════════════════
    //  公開 API
    // ══════════════════════════════════════════════════════════

    /** 開始在背景監聽 TCP 連線（在 PairingActivity 一進入 HOSTING 狀態就呼叫）。 */
    public void startListening() {
        executor.execute(() -> {
            try {
                serverSocket = new ServerSocket(WifiDirectManager.TRANSFER_PORT);
                Log.i(TAG, "Listening on port " + WifiDirectManager.TRANSFER_PORT);
                clientSocket = serverSocket.accept();
                out = new DataOutputStream(clientSocket.getOutputStream());
                in  = new DataInputStream(clientSocket.getInputStream());
                Log.i(TAG, "Client connected: " + clientSocket.getRemoteSocketAddress());
                doHandshake();
                postProgress(TransferProgress.connected());
            } catch (IOException | TransferProtocol.InvalidPacketException e) {
                if (!cancelled) {
                    Log.e(TAG, "Listen/accept failed", e);
                    postProgress(TransferProgress.error("連線失敗：" + e.getMessage()));
                }
            }
        });
    }

    /** 加入待傳檔案（由 TransferActivity 的檔案選擇器呼叫）。 */
    public void enqueue(List<Uri> uris) {
        fileQueue.addAll(uris);
    }

    /** 開始依序傳送已加入佇列的檔案（需在 startListening 成功並握手後呼叫）。 */
    public void startSending() {
        if (clientSocket == null || clientSocket.isClosed()) {
            postProgress(TransferProgress.error("接收方尚未連線"));
            return;
        }
        executor.execute(this::doSendAllFiles);
    }

    /** 取消傳輸並關閉所有資源。 */
    public void cancel() {
        cancelled = true;
        try { sendPacket(TransferProtocol.makeCancel(), null); } catch (Exception ignored) {}
        close();
        postProgress(TransferProgress.cancelled());
    }

    /** 傳輸進度 LiveData，供 TransferActivity 觀察。 */
    public LiveData<TransferProgress> getProgress() { return progressLd; }

    // ══════════════════════════════════════════════════════════
    //  握手
    // ══════════════════════════════════════════════════════════

    private void doHandshake() throws IOException, TransferProtocol.InvalidPacketException {
        // 讀取 CLIENT 的 HANDSHAKE（含對方名字）
        byte[] hdrBuf = readBytes(TransferProtocol.HEADER_SIZE);
        TransferProtocol.Header h = TransferProtocol.decodeHeader(hdrBuf);
        if (h.type != TransferProtocol.TYPE_HANDSHAKE)
            throw new TransferProtocol.InvalidPacketException("Expected HANDSHAKE, got " + h.type);

        // 讀取對方名字 payload
        int nameLen = h.metaLen & 0xFFFF;
        if (nameLen > 0) {
            byte[] nameBytes = readBytes(nameLen);
            peerName = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);
        }

        // 回傳 HANDSHAKE_ACK，帶上自己的名字
        String myName = "";
        try {
            com.kisslink.data.repository.UserProfileRepository repo =
                com.kisslink.data.repository.UserProfileRepository.getInstance(context);
            com.kisslink.model.UserProfile profile = repo.getUserProfile();
            if (profile != null && profile.getName() != null) myName = profile.getName();
        } catch (Exception ignored) {}

        byte[] myNameBytes = myName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        sendPacket(TransferProtocol.makeHandshakeAck(myNameBytes.length), myNameBytes);
        Log.d(TAG, "Handshake complete, peerName=" + peerName);
    }

    // ══════════════════════════════════════════════════════════
    //  檔案傳送
    // ══════════════════════════════════════════════════════════

    private void doSendAllFiles() {
        ContentResolver cr = context.getContentResolver();
        int total = fileQueue.size();

        try {
            for (int i = 0; i < total && !cancelled; i++) {
                Uri uri = fileQueue.get(i);
                sendOneFile(cr, uri, i, total);
            }
            if (!cancelled) {
                postProgress(TransferProgress.allDone(total));
                Log.i(TAG, "All " + total + " file(s) sent");
            }
        } catch (IOException | TransferProtocol.InvalidPacketException e) {
            if (!cancelled) {
                Log.e(TAG, "Send error", e);
                fireFileCompleted(curName, curSize, 0, false); // 進行中的檔案記為失敗
                postProgress(TransferProgress.error("傳送失敗：" + e.getMessage()));
            }
        } finally {
            close();
        }
    }

    private void sendOneFile(ContentResolver cr, Uri uri, int idx, int total)
            throws IOException, TransferProtocol.InvalidPacketException {

        String name = FileUtils.getFileName(cr, uri);
        long   size = FileUtils.getFileSize(cr, uri);
        String mime = cr.getType(uri);
        curName = name; curSize = size; // 標記進行中（供失敗回報）

        // 1. 發送 FILE_META（JSON）
        JSONObject meta = new JSONObject();
        try {
            meta.put("name", name);
            meta.put("size", size);
            meta.put("mime", mime != null ? mime : "application/octet-stream");
        } catch (Exception e) { throw new IOException("JSON error", e); }

        byte[] metaBytes = meta.toString().getBytes(StandardCharsets.UTF_8);
        sendPacket(TransferProtocol.makeFileMeta(idx, total, size, metaBytes.length), metaBytes);

        // 2. 等待 READY_ACK
        TransferProtocol.Header ack = readHeader();
        if (ack.type != TransferProtocol.TYPE_READY_ACK)
            throw new TransferProtocol.InvalidPacketException("Expected READY_ACK");

        // 3. 逐塊傳送
        byte[] buf = new byte[TransferProtocol.CHUNK_SIZE];
        long offset = 0;
        long startMs = System.currentTimeMillis();

        try (InputStream fis = cr.openInputStream(uri)) {
            if (fis == null) throw new IOException("Cannot open: " + uri);
            int read;
            while ((read = fis.read(buf)) != -1 && !cancelled) {
                int crc = TransferProtocol.crc32(buf, 0, read);
                sendPacket(TransferProtocol.makeDataChunk(idx, offset, read, crc),
                        Arrays.copyOf(buf, read));
                offset += read;

                long elapsedMs = System.currentTimeMillis() - startMs;
                long speed = elapsedMs > 0 ? offset * 1000 / elapsedMs : 0;
                postProgress(new TransferProgress.Builder()
                        .phase(TransferProgress.Phase.TRANSFERRING)
                        .fileName(name).totalBytes(size).doneBytes(offset)
                        .speedBps(speed).fileIndex(idx).fileCount(total)
                        .build());
            }
        }

        if (cancelled) return;

        // 4. 發送 COMPLETE，等待 COMPLETE_ACK
        sendPacket(TransferProtocol.makeComplete(idx), null);
        TransferProtocol.Header compAck = readHeader();
        if (compAck.type != TransferProtocol.TYPE_COMPLETE_ACK)
            throw new TransferProtocol.InvalidPacketException("Expected COMPLETE_ACK");

        long elapsed = System.currentTimeMillis() - startMs;
        long avgSpeed = elapsed > 0 ? offset * 1000 / elapsed : 0;
        fireFileCompleted(name, size, avgSpeed, true); // 可靠的逐檔成功回報
        curName = null;

        postProgress(new TransferProgress.Builder()
                .phase(TransferProgress.Phase.FILE_DONE)
                .fileName(name).totalBytes(size).doneBytes(size)
                .fileIndex(idx).fileCount(total)
                .build());
        Log.i(TAG, "File sent: " + name + " (" + size + " bytes)");
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

    /** 精確讀取 exactly n bytes（DataInputStream.read 不保證一次讀完）。 */
    private byte[] readBytes(int n) throws IOException {
        byte[] buf = new byte[n];
        in.readFully(buf);
        return buf;
    }

    private void postProgress(TransferProgress p) { progressLd.postValue(p); }

    private void close() {
        try { if (out != null) out.close(); } catch (IOException ignored) {}
        try { if (in  != null) in.close();  } catch (IOException ignored) {}
        try { if (clientSocket != null) clientSocket.close(); } catch (IOException ignored) {}
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
    }

    public void release() {
        cancelled = true;
        close();
        executor.shutdownNow();
    }
}
