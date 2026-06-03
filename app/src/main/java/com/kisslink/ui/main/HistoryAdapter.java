package com.kisslink.ui.main;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.kisslink.R;
import com.kisslink.data.db.TransferRecordEntity;
import com.kisslink.utils.FileUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 傳輸歷史清單的 RecyclerView Adapter（使用 ListAdapter 支援 DiffUtil 動畫）。
 */
public class HistoryAdapter extends ListAdapter<TransferRecordEntity, HistoryAdapter.VH> {

    public interface OnDeleteClick { void onDelete(long id); }

    private final OnDeleteClick onDelete;
    private final SimpleDateFormat sdf =
            new SimpleDateFormat("MM/dd HH:mm", Locale.getDefault());

    public HistoryAdapter(OnDeleteClick onDelete) {
        super(DIFF);
        this.onDelete = onDelete;
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
        h.tvFileName.setText(r.fileName);
        h.tvSize.setText(FileUtils.formatSize(r.fileSizeBytes));
        h.tvTime.setText(sdf.format(new Date(r.timestampMs)));
        h.tvDirection.setText("SEND".equals(r.direction) ? "↑ 傳送" : "↓ 接收");
        h.tvStatus.setText(r.success ? "✓ 成功" : "✗ 失敗");
        h.tvStatus.setTextColor(r.success ? 0xFF2E7D32 : 0xFFB71C1C);
        h.btnDelete.setOnClickListener(v -> onDelete.onDelete(r.id));
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvFileName, tvSize, tvTime, tvDirection, tvStatus;
        ImageButton btnDelete;
        VH(View v) {
            super(v);
            tvFileName  = v.findViewById(R.id.tvFileName);
            tvSize      = v.findViewById(R.id.tvSize);
            tvTime      = v.findViewById(R.id.tvTime);
            tvDirection = v.findViewById(R.id.tvDirection);
            tvStatus    = v.findViewById(R.id.tvStatus);
            btnDelete   = v.findViewById(R.id.btnDelete);
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
