package com.kisslink.ui.main;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kisslink.R;
import com.kisslink.ui.ThemeManager;

public class HistoryActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        findViewById(R.id.btnHistoryBack).setOnClickListener(v -> finish());

        MainViewModel viewModel = new ViewModelProvider(this).get(MainViewModel.class);
        RecyclerView rv = findViewById(R.id.rvHistoryList);

        HistoryAdapter adapter = new HistoryAdapter(record -> {
            if (record.filePath == null || record.filePath.isEmpty()) return;

            Uri fileUri = Uri.parse(record.filePath);
            String mime = null;
            try {
                mime = getContentResolver().getType(fileUri);
            } catch (Exception ignored) {}

            boolean isPreviewable = mime != null &&
                    (mime.startsWith("image/") || mime.startsWith("video/"));

            if (isPreviewable) {
                FilePreviewFragment.newInstance(record.filePath)
                        .show(getSupportFragmentManager(), "preview");
            } else {
                // 系統 App 開啟
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(fileUri, mime);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(intent);
                } catch (android.content.ActivityNotFoundException e) {
                    Toast.makeText(this, "找不到可開啟此檔案的應用程式", Toast.LENGTH_SHORT).show();
                }
            }
        });

        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
        viewModel.getRecentRecords().observe(this, adapter::submitList);
    }
}
