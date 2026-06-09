package com.kisslink.ui.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.imageview.ShapeableImageView;
import com.kisslink.R;
import com.kisslink.transfer.TransferProtocol;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Beam 中央下方的項目清單：已選待送、傳輸中（含百分比）、完成（勾）。
 * 相片/影片載入小縮圖；檔案用圖示。
 */
public class SendListAdapter extends RecyclerView.Adapter<SendListAdapter.VH> {

    public interface OnRemove { void onRemove(int position); }

    private final List<SendRow> rows = new ArrayList<>();
    private final ExecutorService thumbPool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    @androidx.annotation.Nullable private OnRemove onRemove;

    public void setOnRemove(@androidx.annotation.Nullable OnRemove l) { this.onRemove = l; }

    @SuppressWarnings("NotifyDataSetChanged")
    public void submit(@NonNull List<SendRow> next) {
        rows.clear();
        rows.addAll(next);
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_send, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        SendRow r = rows.get(position);
        Context ctx = h.itemView.getContext();

        h.name.setText(r.name);

        // 中間 meta：傳輸中顯示方向，否則大小
        if (r.percent >= 0 && !r.done) {
            h.meta.setText(r.incoming ? ctx.getString(R.string.receiving)
                                      : ctx.getString(R.string.sending));
        } else {
            h.meta.setText(r.sizeLabel);
        }

        // 右側狀態
        if (r.done) {
            h.status.setText("✓");
            h.status.setTextColor(ctx.getColor(R.color.beam_accent));
        } else if (r.percent >= 0) {
            h.status.setText(r.percent + "%");
            h.status.setTextColor(ctx.getColor(R.color.beam_accent));
        } else {
            h.status.setText(r.sizeLabel.isEmpty() ? "" : r.sizeLabel);
            h.status.setTextColor(ctx.getColor(R.color.beam_muted));
            h.meta.setText(itemTypeLabel(ctx, r.itemType));
        }

        // 移除鈕（待傳清單）
        h.remove.setVisibility(r.removable && !r.done && r.percent < 0 ? View.VISIBLE : View.GONE);
        h.remove.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && onRemove != null) onRemove.onRemove(pos);
        });

        // 縮圖
        h.thumb.setImageResource(iconFor(r.itemType));
        h.thumb.setPadding(dp(ctx, 9), dp(ctx, 9), dp(ctx, 9), dp(ctx, 9));
        h.thumbTag = r.thumbUri;
        if (r.isVisualMedia() && r.thumbUri != null) {
            final Uri want = r.thumbUri;
            thumbPool.execute(() -> {
                Bitmap bm = decodeThumb(ctx, want, dp(ctx, 38));
                if (bm == null) return;
                main.post(() -> {
                    if (want.equals(h.thumbTag)) {
                        h.thumb.setPadding(0, 0, 0, 0);
                        h.thumb.setImageBitmap(bm);
                    }
                });
            });
        }
    }

    @Override public int getItemCount() { return rows.size(); }

    private static String itemTypeLabel(Context c, byte t) {
        switch (t) {
            case TransferProtocol.ITEM_VCARD: return "名片";
            case TransferProtocol.ITEM_PHOTO: return "相片／影片";
            default: return "檔案";
        }
    }

    private static int iconFor(byte t) {
        switch (t) {
            case TransferProtocol.ITEM_PHOTO: return R.drawable.ic_image;
            case TransferProtocol.ITEM_VCARD: return R.drawable.ic_person;
            default: return R.drawable.ic_file;
        }
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static Bitmap decodeThumb(Context ctx, Uri uri, int targetPx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            int half = Math.min(bounds.outWidth, bounds.outHeight) / 2;
            while (half / sample > targetPx) sample *= 2;
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = Math.max(1, sample);
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, opt);
            }
        } catch (Exception e) {
            return null;
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        final ShapeableImageView thumb;
        final TextView name, meta, status;
        final android.widget.ImageButton remove;
        @androidx.annotation.Nullable Uri thumbTag;
        VH(@NonNull View v) {
            super(v);
            thumb  = v.findViewById(R.id.ivThumb);
            name   = v.findViewById(R.id.tvName);
            meta   = v.findViewById(R.id.tvMeta);
            status = v.findViewById(R.id.tvStatus);
            remove = v.findViewById(R.id.ibRemove);
        }
    }
}
