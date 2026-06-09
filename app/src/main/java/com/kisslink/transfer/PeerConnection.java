package com.kisslink.transfer;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 雙向 peer 傳輸通道——配對連線建立後,雙方各持一個 {@link Socket}。
 *
 * <p>TCP 為全雙工:本端 {@code out} → 對端 {@code in} 是一條獨立位元流,反向亦然,
 * 互不干擾。因此只需:
 * <ul>
 *   <li><b>sender thread</b>:從佇列取 {@link SendItem},寫 META + CHUNK×N + COMPLETE 到 {@code out}。</li>
 *   <li><b>reader thread</b>:從 {@code in} 讀對方送來的項目,存到 下載/KissLink。</li>
 * </ul>
 * 任一端隨時可送、可多輪,真正對等。無 ACK——TCP 保證順序送達,CRC32 驗每個 chunk。
 *
 * <p>歷史紀錄走同步回呼 {@link Listener#onItemCompleted}(不經會合併的 LiveData),
 * 保證一項一次、不漏不重;UI 進度走 {@link #getProgress()} LiveData。
 */
public class PeerConnection {

    private static final String TAG = "PeerConnection";
    private static final String SAVE_DIR = Environment.DIRECTORY_DOWNLOADS + "/KissLink";

    public interface Listener {
        /** 一個項目傳輸結束(成功或失敗)時同步呼叫一次。 */
        void onItemCompleted(boolean sent, String name, long size, long avgSpeedBps,
                             boolean success, byte itemType);
        /** 連線中斷(對方關閉 / 錯誤)。 */
        void onDisconnected();
    }

    private final Context context;
    private final Socket socket;
    private final Listener listener;

    private final BlockingQueue<Object> outQueue = new LinkedBlockingQueue<>();
    private static final Object STOP = new Object();

    private final MutableLiveData<TransferProgress> progressLd =
            new MutableLiveData<>(TransferProgress.connected());

    private static final int HEARTBEAT_INTERVAL_MS = 2500;
    private static final int LIVENESS_TIMEOUT_MS   = 7000;

    private volatile boolean running = false;
    private Thread readerThread, senderThread, heartbeatThread;
    private final Object writeLock = new Object();
    @Nullable private BufferedOutputStream out;

    public PeerConnection(@NonNull Context context, @NonNull Socket socket, @NonNull Listener listener) {
        this.context  = context.getApplicationContext();
        this.socket   = socket;
        this.listener = listener;
    }

    public LiveData<TransferProgress> getProgress() { return progressLd; }

    /** 連線是否仍存活(心跳/資料在 {@value #LIVENESS_TIMEOUT_MS}ms 內有往來)。 */
    public boolean isAlive() { return running; }

    public void start() {
        if (running) return;
        running = true;
        try {
            out = new BufferedOutputStream(socket.getOutputStream());
            socket.setSoTimeout(LIVENESS_TIMEOUT_MS); // 逾時內沒收到任何封包(含心跳)→ 視為斷線
        } catch (IOException e) {
            Log.e(TAG, "start failed", e);
            running = false;
            listener.onDisconnected();
            return;
        }
        readerThread    = new Thread(this::readLoop, "peer-reader");
        senderThread    = new Thread(this::sendLoop, "peer-sender");
        heartbeatThread = new Thread(this::heartbeatLoop, "peer-heartbeat");
        readerThread.start();
        senderThread.start();
        heartbeatThread.start();
        Log.i(TAG, "PeerConnection started");
    }

    /** 所有 socket 寫入的唯一出口:同步序列化,確保心跳不會插進資料封包中間造成錯位。 */
    private void writeFrame(byte[] header, @Nullable byte[] payload, int off, int len) throws IOException {
        synchronized (writeLock) {
            if (out == null) throw new IOException("stream closed");
            out.write(header);
            if (payload != null && len > 0) out.write(payload, off, len);
            out.flush();
        }
    }

    /** 心跳:每 {@value #HEARTBEAT_INTERVAL_MS}ms 送一個,讓對端的 SO_TIMEOUT 不會誤判斷線。 */
    private void heartbeatLoop() {
        byte[] hb = TransferProtocol.encodeHeader(TransferProtocol.makeHeartbeat());
        while (running) {
            try { Thread.sleep(HEARTBEAT_INTERVAL_MS); } catch (InterruptedException e) { break; }
            if (!running) break;
            try { writeFrame(hb, null, 0, 0); }
            catch (IOException e) { Log.w(TAG, "heartbeat failed: " + e.getMessage()); break; }
        }
    }

    /** 排入待送項目(任一時刻、可多次)。 */
    public void sendItems(@NonNull List<SendItem> items) {
        for (SendItem it : items) outQueue.offer(it);
    }

    public void close() {
        running = false;
        outQueue.offer(STOP);
        if (heartbeatThread != null) heartbeatThread.interrupt();
        try { socket.close(); } catch (IOException ignored) {}
        Log.d(TAG, "PeerConnection closed");
    }

    // ══════════════════════════════════════════════════════════
    //  傳送
    // ══════════════════════════════════════════════════════════

    private void sendLoop() {
        try {
            // HELLO 開場
            writeFrame(TransferProtocol.encodeHeader(TransferProtocol.makeHello()), null, 0, 0);

            while (running) {
                Object o = outQueue.take();
                if (o == STOP) break;
                if (!(o instanceof SendItem)) continue;
                sendOne((SendItem) o);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            Log.w(TAG, "sendLoop ended: " + e.getMessage());
        }
    }

    private void sendOne(SendItem item) {
        long started = System.currentTimeMillis();
        boolean ok = false;
        try {
            // META
            JSONObject meta = new JSONObject();
            meta.put("n", item.name);
            meta.put("m", item.mime);
            byte[] metaBytes = meta.toString().getBytes(StandardCharsets.UTF_8);
            long size = item.size >= 0 ? item.size : 0;
            TransferProtocol.Header mh =
                    TransferProtocol.makeItemMeta(0, item.itemType, size, metaBytes.length);
            writeFrame(TransferProtocol.encodeHeader(mh), metaBytes, 0, metaBytes.length);

            // CHUNKS
            byte[] buf = new byte[TransferProtocol.CHUNK_SIZE];
            long sent = 0; long offset = 0;
            try (InputStream in = openItemInput(item)) {
                int r;
                while ((r = in.read(buf)) > 0) {
                    int crc = TransferProtocol.crc32(buf, 0, r);
                    TransferProtocol.Header ch =
                            TransferProtocol.makeDataChunk(0, offset, r, crc);
                    writeFrame(TransferProtocol.encodeHeader(ch), buf, 0, r);
                    offset += r; sent += r;
                    emitProgress(true, item.name, size, sent, started);
                }
            }
            // COMPLETE
            writeFrame(TransferProtocol.encodeHeader(TransferProtocol.makeComplete(0)), null, 0, 0);
            ok = true;
            emitDone(true, item.name, size);
        } catch (Exception e) {
            Log.e(TAG, "sendOne failed: " + item.name, e);
        } finally {
            long avg = avgSpeed(item.size, started);
            listener.onItemCompleted(true, item.name, Math.max(item.size, 0), avg, ok, item.itemType);
        }
    }

    private InputStream openItemInput(SendItem item) throws IOException {
        if (item.bytes != null) return new java.io.ByteArrayInputStream(item.bytes);
        if (item.uri != null) {
            InputStream in = context.getContentResolver().openInputStream(item.uri);
            if (in == null) throw new IOException("openInputStream null: " + item.uri);
            return in;
        }
        throw new IOException("SendItem has no source");
    }

    // ══════════════════════════════════════════════════════════
    //  接收
    // ══════════════════════════════════════════════════════════

    private void readLoop() {
        try {
            InputStream in = socket.getInputStream();
            byte[] header = new byte[TransferProtocol.HEADER_SIZE];
            ReceivingItem cur = null;

            while (running) {
                readFully(in, header, header.length);
                TransferProtocol.Header h = TransferProtocol.decodeHeader(header);

                switch (h.type) {
                    case TransferProtocol.TYPE_HELLO:
                        break;

                    case TransferProtocol.TYPE_FILE_META: {
                        byte[] mb = new byte[h.metaLen];
                        readFully(in, mb, mb.length);
                        JSONObject meta = new JSONObject(new String(mb, StandardCharsets.UTF_8));
                        String name = meta.optString("n", "received_" + System.currentTimeMillis());
                        String mime = meta.optString("m", "application/octet-stream");
                        if (cur != null) cur.abort();
                        cur = new ReceivingItem(name, mime, h.totalSize, h.itemType);
                        emitProgress(false, name, h.totalSize, 0, cur.started);
                        break;
                    }

                    case TransferProtocol.TYPE_DATA_CHUNK: {
                        byte[] data = new byte[h.chunkLen];
                        readFully(in, data, data.length);
                        if (cur != null) {
                            int crc = TransferProtocol.crc32(data, 0, data.length);
                            if (crc != h.crc32) { Log.w(TAG, "CRC mismatch"); cur.corrupt = true; }
                            cur.write(data);
                            emitProgress(false, cur.name, cur.size, cur.received, cur.started);
                        }
                        break;
                    }

                    case TransferProtocol.TYPE_COMPLETE: {
                        if (cur != null) {
                            boolean ok = cur.finish();
                            long avg = avgSpeed(cur.size, cur.started);
                            listener.onItemCompleted(false, cur.name, cur.size, avg, ok, cur.itemType);
                            emitDone(false, cur.name, cur.size);
                            cur = null;
                        }
                        break;
                    }

                    case TransferProtocol.TYPE_CANCEL:
                        if (cur != null) { cur.abort(); cur = null; }
                        break;

                    default:
                        break;
                }
            }
        } catch (EOFException e) {
            Log.i(TAG, "Peer closed the connection");
        } catch (Exception e) {
            Log.w(TAG, "readLoop ended: " + e.getMessage());
        } finally {
            running = false;
            listener.onDisconnected();
        }
    }

    /** 一個接收中的項目:邊收邊寫入 下載/KissLink(MediaStore,IS_PENDING)。 */
    private final class ReceivingItem {
        final String name, mime;
        final long size;
        final byte itemType;
        final long started = System.currentTimeMillis();
        long received = 0;
        boolean corrupt = false;
        @Nullable Uri target;
        @Nullable OutputStream out;

        ReceivingItem(String name, String mime, long size, byte itemType) {
            this.name = name; this.mime = mime; this.size = size; this.itemType = itemType;
            try {
                ContentResolver cr = context.getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    v.put(MediaStore.Downloads.RELATIVE_PATH, SAVE_DIR);
                    v.put(MediaStore.Downloads.IS_PENDING, 1);
                }
                target = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (target != null) out = cr.openOutputStream(target);
            } catch (Exception e) {
                Log.e(TAG, "open receive output failed: " + name, e);
            }
        }

        void write(byte[] data) throws IOException {
            if (out != null) { out.write(data); received += data.length; }
        }

        boolean finish() {
            try { if (out != null) { out.flush(); out.close(); } } catch (IOException ignored) {}
            out = null;
            try {
                if (target != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.IS_PENDING, 0);
                    context.getContentResolver().update(target, v, null, null);
                }
            } catch (Exception ignored) {}
            return !corrupt && target != null;
        }

        void abort() {
            try { if (out != null) out.close(); } catch (IOException ignored) {}
            out = null;
            try { if (target != null) context.getContentResolver().delete(target, null, null); }
            catch (Exception ignored) {}
        }
    }

    // ══════════════════════════════════════════════════════════
    //  工具
    // ══════════════════════════════════════════════════════════

    private void emitProgress(boolean sending, String name, long total, long done, long started) {
        long speed = 0;
        long elapsed = System.currentTimeMillis() - started;
        if (elapsed > 0) speed = done * 1000L / elapsed;
        progressLd.postValue(new TransferProgress.Builder()
                .phase(TransferProgress.Phase.TRANSFERRING)
                .fileName((sending ? "傳送 " : "接收 ") + name)
                .totalBytes(total).doneBytes(done).speedBps(speed)
                .build());
    }

    private void emitDone(boolean sending, String name, long size) {
        progressLd.postValue(new TransferProgress.Builder()
                .phase(TransferProgress.Phase.FILE_DONE)
                .fileName(name).totalBytes(size).doneBytes(size)
                .build());
    }

    private static long avgSpeed(long size, long startedMs) {
        long elapsed = System.currentTimeMillis() - startedMs;
        if (size <= 0 || elapsed <= 0) return 0;
        return size * 1000L / elapsed;
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
    }
}
