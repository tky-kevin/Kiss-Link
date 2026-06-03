package com.kisslink.ui.transfer;

import android.content.ContentResolver;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.kisslink.R;
import com.kisslink.utils.FileUtils;

import java.util.List;

/**
 * 傳送方「已選檔案清單」的 RecyclerView Adapter。
 */
public class SelectedFileAdapter extends RecyclerView.Adapter<SelectedFileAdapter.VH> {

    private final List<Uri>     uris;
    private final ContentResolver cr;

    public SelectedFileAdapter(List<Uri> uris, ContentResolver cr) {
        this.uris = uris;
        this.cr   = cr;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_selected_file, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Uri uri  = uris.get(pos);
        String name = FileUtils.getFileName(cr, uri);
        long   size = FileUtils.getFileSize(cr, uri);

        h.tvName.setText(name);
        h.tvSize.setText(FileUtils.formatSize(size));
        h.ivIcon.setImageResource(FileUtils.guessIcon(name));
        h.btnRemove.setOnClickListener(v -> {
            int p = h.getAdapterPosition();
            if (p != RecyclerView.NO_ID) {
                uris.remove(p);
                notifyItemRemoved(p);
            }
        });
    }

    @Override public int getItemCount() { return uris.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView  tvName, tvSize;
        ImageButton btnRemove;
        VH(View v) {
            super(v);
            ivIcon    = v.findViewById(R.id.ivIcon);
            tvName    = v.findViewById(R.id.tvName);
            tvSize    = v.findViewById(R.id.tvSize);
            btnRemove = v.findViewById(R.id.btnRemove);
        }
    }
}
