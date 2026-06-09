package com.kisslink.ui.main;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.kisslink.R;
import com.kisslink.data.db.TransferRecordEntity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 傳輸歷史清單的 RecyclerView Adapter（使用 ListAdapter 支援 DiffUtil 動畫）。
 * 支援縮圖載入（圖片/影片）和 click 事件回呼。
 */
public class HistoryAdapter extends ListAdapter<TransferRecordEntity, HistoryAdapter.VH> {

    public interface OnItemClickListener {
        void onItemClick(TransferRecordEntity record);
    }

    private final OnItemClickListener clickListener;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final SimpleDateFormat sdf = new SimpleDateFormat("MM/dd HH:mm", Locale.getDefault());

    public HistoryAdapter(OnItemClickListener listener) {
        super(DIFF);
        this.clickListener = listener;
    }

    /** 無 click listener 的建構子（相容舊呼叫端）*/
    public HistoryAdapter() {
        this(null);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        TransferRecordEntity r = getItem(pos);
        boolean isSend = "SEND".equals(r.direction);

        // 方向 icon 和顏色
        h.tvDirectionIcon.setText(isSend ? "↑" : "↓");
        int dirColor = isSend
                ? ContextCompat.getColor(h.itemView.getContext(), R.color.btn_send)
                : ContextCompat.getColor(h.itemView.getContext(), R.color.accent);
        h.tvDirectionIcon.setTextColor(dirColor);

        // 檔名
        h.tvFileName.setText(r.fileName != null ? r.fileName : "未知檔案");

        // 時間（單獨）
        h.tvMeta.setText(sdf.format(new Date(r.timestampMs)));

        // 對方名字（右側獨立顯示）
        if (h.tvPeerName != null) {
            boolean hasPeer = r.peerDeviceName != null && !r.peerDeviceName.isEmpty();
            if (hasPeer) {
                h.tvPeerName.setText(r.peerDeviceName);
                h.tvPeerName.setVisibility(View.VISIBLE);
            } else {
                h.tvPeerName.setVisibility(View.GONE);
            }
        }

        // 大小 + 速度 + 成功/失敗
        String size = formatSize(r.fileSizeBytes);
        String speed = r.avgSpeedBps > 0 ? " · " + formatSize(r.avgSpeedBps) + "/s" : "";
        String status = r.success ? "" : " · 失敗";
        h.tvSize.setText(size + speed + status);

        // 狀態色條（左邊緣）
        int statusColor = r.success
                ? ContextCompat.getColor(h.itemView.getContext(), R.color.notion_success)
                : ContextCompat.getColor(h.itemView.getContext(), R.color.notion_error);
        if (h.ivStatus != null) {
            h.ivStatus.setBackgroundColor(statusColor);
        }

        // 縮圖載入
        loadThumbnail(h, r);

        // Click listener
        h.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onItemClick(r);
        });
    }

    private void loadThumbnail(@NonNull VH h, TransferRecordEntity r) {
        h.ivThumb.setVisibility(View.GONE);
        h.ivThumbPlay.setVisibility(View.GONE);
        h.ivFileIcon.setVisibility(View.GONE);

        if (r.filePath == null || r.filePath.isEmpty()) {
            // 沒有路徑：顯示通用 icon
            h.ivFileIcon.setVisibility(View.VISIBLE);
            h.ivFileIcon.setImageResource(android.R.drawable.ic_menu_save);
            return;
        }

        Uri uri;
        try {
            uri = Uri.parse(r.filePath);
        } catch (Exception e) {
            h.ivFileIcon.setVisibility(View.VISIBLE);
            h.ivFileIcon.setImageResource(android.R.drawable.ic_menu_save);
            return;
        }

        Context ctx = h.itemView.getContext();
        String mime = null;
        try {
            mime = ctx.getContentResolver().getType(uri);
        } catch (Exception ignored) {}

        if (mime != null && mime.startsWith("image/")) {
            h.ivThumb.setVisibility(View.VISIBLE);
            final Uri finalUri = uri;
            executor.execute(() -> {
                try {
                    Bitmap bmp = loadScaledBitmap(ctx, finalUri, 160);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (h.ivThumb.isAttachedToWindow() && bmp != null) {
                            h.ivThumb.setImageBitmap(bmp);
                        }
                    });
                } catch (Exception ignored) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        h.ivThumb.setVisibility(View.GONE);
                        h.ivFileIcon.setVisibility(View.VISIBLE);
                        h.ivFileIcon.setImageResource(android.R.drawable.ic_menu_gallery);
                    });
                }
            });
        } else if (mime != null && mime.startsWith("video/")) {
            h.ivThumb.setVisibility(View.VISIBLE);
            h.ivThumbPlay.setVisibility(View.VISIBLE);
            final Uri finalUri = uri;
            executor.execute(() -> {
                try {
                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                    mmr.setDataSource(ctx, finalUri);
                    Bitmap frame = mmr.getFrameAtTime(0);
                    mmr.release();
                    Bitmap f = frame;
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (h.ivThumb.isAttachedToWindow() && f != null) {
                            h.ivThumb.setImageBitmap(f);
                        }
                    });
                } catch (Exception ignored) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        h.ivThumb.setVisibility(View.GONE);
                        h.ivThumbPlay.setVisibility(View.GONE);
                        h.ivFileIcon.setVisibility(View.VISIBLE);
                        h.ivFileIcon.setImageResource(android.R.drawable.ic_media_play);
                    });
                }
            });
        } else {
            h.ivFileIcon.setVisibility(View.VISIBLE);
            h.ivFileIcon.setImageResource(getFileIcon(mime));
        }
    }

    private Bitmap loadScaledBitmap(Context ctx, Uri uri, int maxDim) throws Exception {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(ctx.getContentResolver().openInputStream(uri), null, opts);
        int minDim = Math.min(opts.outWidth, opts.outHeight);
        opts.inSampleSize = minDim > maxDim ? minDim / maxDim : 1;
        opts.inJustDecodeBounds = false;
        return BitmapFactory.decodeStream(ctx.getContentResolver().openInputStream(uri), null, opts);
    }

    private int getFileIcon(String mime) {
        if (mime == null) return android.R.drawable.ic_menu_save;
        if (mime.startsWith("audio/")) return android.R.drawable.ic_media_play;
        if (mime.contains("pdf")) return android.R.drawable.ic_menu_agenda;
        return android.R.drawable.ic_menu_save;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvDirectionIcon, tvFileName, tvMeta, tvSize, tvPeerName;
        ImageView ivThumb, ivThumbPlay, ivFileIcon;
        View ivStatus;

        VH(View v) {
            super(v);
            tvDirectionIcon = v.findViewById(R.id.tvDirectionIcon);
            tvFileName      = v.findViewById(R.id.tvFileName);
            tvMeta          = v.findViewById(R.id.tvMeta);
            tvSize          = v.findViewById(R.id.tvSize);
            tvPeerName      = v.findViewById(R.id.tvPeerName);
            ivThumb         = v.findViewById(R.id.ivThumb);
            ivThumbPlay     = v.findViewById(R.id.ivThumbPlay);
            ivFileIcon      = v.findViewById(R.id.ivFileIcon);
            ivStatus        = v.findViewById(R.id.ivStatus);
        }
    }

    private static final DiffUtil.ItemCallback<TransferRecordEntity> DIFF =
            new DiffUtil.ItemCallback<TransferRecordEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull TransferRecordEntity a,
                                               @NonNull TransferRecordEntity b) {
                    return a.id == b.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull TransferRecordEntity a,
                                                  @NonNull TransferRecordEntity b) {
                    return a.id == b.id && a.success == b.success;
                }
            };
}
