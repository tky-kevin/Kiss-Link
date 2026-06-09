package com.kisslink.ui.main;

import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.kisslink.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 全螢幕檔案預覽 DialogFragment，支援多檔案左右滑動切換（ViewPager2）。
 * 圖片直接顯示縮圖；影片顯示第一幀 + play icon；其他顯示通用 icon。
 * 向上滑超過 120px 或點擊 X / 外部區域關閉。
 * API 31+ 對 host Activity 套模糊效果，dismiss 時清除。
 */
public class FilePreviewFragment extends DialogFragment {

    private static final String ARG_URIS = "uris";

    private List<String> uris = new ArrayList<>();
    private ViewPager2 viewPager;
    private TextView tvPageIndicator;
    private float swipeStartY = 0f;

    // ══════════════════════════════════════════════════════════
    //  Factory Methods
    // ══════════════════════════════════════════════════════════

    public static FilePreviewFragment newInstance(List<String> uris) {
        Bundle args = new Bundle();
        args.putStringArrayList(ARG_URIS, new ArrayList<>(uris));
        FilePreviewFragment f = new FilePreviewFragment();
        f.setArguments(args);
        return f;
    }

    public static FilePreviewFragment newInstance(String uri) {
        return newInstance(Collections.singletonList(uri));
    }

    // ══════════════════════════════════════════════════════════
    //  生命週期
    // ══════════════════════════════════════════════════════════

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        if (args != null) {
            List<String> list = args.getStringArrayList(ARG_URIS);
            if (list != null) uris = list;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_file_preview, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View rootView, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(rootView, savedInstanceState);

        viewPager = rootView.findViewById(R.id.viewPager);
        tvPageIndicator = rootView.findViewById(R.id.tvPageIndicator);
        ImageButton btnClose = rootView.findViewById(R.id.btnClosePreview);

        // 設定 Adapter
        PreviewPagerAdapter adapter = new PreviewPagerAdapter(uris);
        viewPager.setAdapter(adapter);

        // 頁碼指示（多頁才顯示）
        if (uris.size() > 1) {
            tvPageIndicator.setVisibility(View.VISIBLE);
            updatePageIndicator(0);
            viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int position) {
                    updatePageIndicator(position);
                }
            });
        }

        // X 關閉按鈕
        btnClose.setOnClickListener(v -> dismiss());

        // 向上滑動關閉（套在整個 rootView）
        rootView.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    swipeStartY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_UP:
                    float deltaY = swipeStartY - event.getRawY(); // 正值 = 往上滑
                    if (deltaY > 120) {
                        dismiss();
                        return true;
                    }
                    break;
            }
            return false;
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
            lp.dimAmount = 0.8f;
            dialog.getWindow().setAttributes(lp);
            dialog.setCanceledOnTouchOutside(true);
        }
        // 模糊 host Activity
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isAdded()) {
            requireActivity().getWindow().getDecorView().setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    18f, 18f, Shader.TileMode.CLAMP));
        }
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        // 解除模糊
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isAdded()) {
            requireActivity().getWindow().getDecorView().setRenderEffect(null);
        }
    }

    // ══════════════════════════════════════════════════════════
    //  頁碼指示
    // ══════════════════════════════════════════════════════════

    private void updatePageIndicator(int position) {
        tvPageIndicator.setText((position + 1) + " / " + uris.size());
    }

    // ══════════════════════════════════════════════════════════
    //  內部 ViewPager2 Adapter
    // ══════════════════════════════════════════════════════════

    private class PreviewPagerAdapter extends RecyclerView.Adapter<PreviewPagerAdapter.PH> {

        private final List<String> adapterUris;
        private final ExecutorService executor = Executors.newCachedThreadPool();

        PreviewPagerAdapter(List<String> uris) {
            this.adapterUris = uris;
        }

        @NonNull
        @Override
        public PH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_preview_page, parent, false);
            return new PH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull PH holder, int position) {
            String uriStr = adapterUris.get(position);
            Uri uri = Uri.parse(uriStr);
            String mimeType = null;
            try {
                mimeType = requireContext().getContentResolver().getType(uri);
            } catch (Exception ignored) {}

            // 重置所有 View
            holder.ivPreview.setVisibility(View.GONE);
            holder.ivFileIcon.setVisibility(View.GONE);
            holder.ivPlayOverlay.setVisibility(View.GONE);
            holder.tvFileName.setText(getFileName(uri));

            if (mimeType != null && mimeType.startsWith("image/")) {
                // 圖片：背景載入 bitmap
                holder.ivPreview.setVisibility(View.VISIBLE);
                executor.execute(() -> {
                    try {
                        Bitmap bmp = BitmapFactory.decodeStream(
                            requireContext().getContentResolver().openInputStream(uri));
                        new Handler(Looper.getMainLooper()).post(() -> {
                            if (holder.ivPreview.isAttachedToWindow()) {
                                holder.ivPreview.setImageBitmap(bmp);
                            }
                        });
                    } catch (Exception e) {
                        new Handler(Looper.getMainLooper()).post(() -> {
                            holder.ivPreview.setVisibility(View.GONE);
                            holder.ivFileIcon.setVisibility(View.VISIBLE);
                            holder.ivFileIcon.setImageResource(android.R.drawable.ic_menu_gallery);
                        });
                    }
                });
            } else if (mimeType != null && mimeType.startsWith("video/")) {
                // 影片：擷取第一幀 + play icon overlay
                holder.ivPreview.setVisibility(View.VISIBLE);
                holder.ivPlayOverlay.setVisibility(View.VISIBLE);
                executor.execute(() -> {
                    try {
                        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                        mmr.setDataSource(requireContext(), uri);
                        Bitmap frame = mmr.getFrameAtTime(0);
                        mmr.release();
                        Bitmap finalFrame = frame;
                        new Handler(Looper.getMainLooper()).post(() -> {
                            if (holder.ivPreview.isAttachedToWindow() && finalFrame != null) {
                                holder.ivPreview.setImageBitmap(finalFrame);
                            }
                        });
                    } catch (Exception e) {
                        new Handler(Looper.getMainLooper()).post(() -> {
                            holder.ivPreview.setVisibility(View.GONE);
                            holder.ivPlayOverlay.setVisibility(View.GONE);
                            holder.ivFileIcon.setVisibility(View.VISIBLE);
                            holder.ivFileIcon.setImageResource(android.R.drawable.ic_media_play);
                        });
                    }
                });
            } else {
                // 其他：通用 icon
                holder.ivFileIcon.setVisibility(View.VISIBLE);
                holder.ivFileIcon.setImageResource(getFileIcon(mimeType));
            }
        }

        private String getFileName(Uri uri) {
            try (android.database.Cursor c = requireContext().getContentResolver().query(
                    uri, new String[]{android.provider.MediaStore.Downloads.DISPLAY_NAME},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) return c.getString(0);
            } catch (Exception ignored) {}
            String lastSegment = uri.getLastPathSegment();
            return lastSegment != null ? lastSegment : "未知檔案";
        }

        private int getFileIcon(String mime) {
            if (mime == null) return android.R.drawable.ic_menu_save;
            if (mime.startsWith("audio/")) return android.R.drawable.ic_media_play;
            if (mime.contains("pdf")) return android.R.drawable.ic_menu_agenda;
            return android.R.drawable.ic_menu_save;
        }

        @Override
        public int getItemCount() {
            return adapterUris.size();
        }

        class PH extends RecyclerView.ViewHolder {
            ImageView ivPreview, ivFileIcon, ivPlayOverlay;
            TextView tvFileName;

            PH(View v) {
                super(v);
                ivPreview    = v.findViewById(R.id.ivPreview);
                ivFileIcon   = v.findViewById(R.id.ivFileIcon);
                ivPlayOverlay = v.findViewById(R.id.ivPlayOverlay);
                tvFileName   = v.findViewById(R.id.tvFileName);
            }
        }
    }
}
