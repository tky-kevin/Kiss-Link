package com.kisslink.ui.main;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kisslink.R;
import com.kisslink.ui.pairing.PairingActivity;
import com.kisslink.ui.pairing.PairingViewModel;
import com.kisslink.util.PermissionHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 應用程式主畫面。
 *
 * <h3>傳送流程（修正後）</h3>
 * <pre>
 *   點「傳送」
 *     → 系統檔案選擇器（多選）
 *       → 選好檔案
 *         → PairingActivity（SENDER + 帶著 URIs）
 *           → NFC 碰觸 → 靜默 Wi-Fi Direct 連線
 *             → TransferActivity（立刻開始傳送）
 * </pre>
 *
 * <h3>接收流程</h3>
 * <pre>
 *   點「接收」→ PairingActivity（RECEIVER）→ NFC 碰觸 → 靜默連線 → 自動接收
 * </pre>
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class MainActivity extends AppCompatActivity {

    private MainViewModel    viewModel;
    private HistoryAdapter   historyAdapter;

    // ── 系統多選檔案選擇器 ─────────────────────────────────────
    private final ActivityResultLauncher<String[]> filePickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.OpenMultipleDocuments(),
                    uris -> {
                        if (uris == null || uris.isEmpty()) return;

                        ArrayList<Uri> selected = new ArrayList<>();
                        for (Uri uri : uris) {
                            // 持久化讀取權限，讓後台 Service 也能讀取此 URI
                            try {
                                getContentResolver().takePersistableUriPermission(
                                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } catch (SecurityException e) {
                                // 某些 Provider 不支援持久化（例如照片選擇器），忽略即可
                            }
                            selected.add(uri);
                        }

                        // 帶著選好的檔案進入配對畫面
                        startActivity(
                                PairingActivity.newIntent(this, PairingViewModel.Role.SENDER, selected));
                    });

    // ══════════════════════════════════════════════════════════
    //  生命週期
    // ══════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewModel = new ViewModelProvider(this).get(MainViewModel.class);

        // ── 按鈕 ────────────────────────────────────────────────
        Button btnSend    = findViewById(R.id.btnSend);
        Button btnReceive = findViewById(R.id.btnReceive);

        btnSend.setOnClickListener(v    -> startSend());
        btnReceive.setOnClickListener(v -> startReceive());

        // ── 歷史清單 ─────────────────────────────────────────────
        RecyclerView rvHistory = findViewById(R.id.rvHistory);
        historyAdapter = new HistoryAdapter(id -> viewModel.deleteRecord(id));
        rvHistory.setLayoutManager(new LinearLayoutManager(this));
        rvHistory.setAdapter(historyAdapter);
        viewModel.getRecentRecords().observe(this, records ->
                historyAdapter.submitList(records));

        // 啟動時請求權限
        if (!PermissionHelper.hasPermissions(this)) {
            PermissionHelper.requestPermissions(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (!PermissionHelper.allGranted(grantResults)) {
            Toast.makeText(this, "需要 Wi-Fi 與檔案權限才能使用", Toast.LENGTH_LONG).show();
        }
    }

    // ══════════════════════════════════════════════════════════
    //  按鈕動作
    // ══════════════════════════════════════════════════════════

    /** 傳送：先打開檔案選擇器，選好後再進配對畫面。 */
    private void startSend() {
        if (!PermissionHelper.hasPermissions(this)) {
            PermissionHelper.requestPermissions(this);
            return;
        }
        // 打開系統多選檔案選擇器（"*/*" = 全部類型）
        filePickerLauncher.launch(new String[]{"*/*"});
    }

    /** 接收：直接進配對畫面等待 NFC 碰觸。 */
    private void startReceive() {
        if (!PermissionHelper.hasPermissions(this)) {
            PermissionHelper.requestPermissions(this);
            return;
        }
        startActivity(PairingActivity.newIntent(this, PairingViewModel.Role.RECEIVER, null));
    }
}
