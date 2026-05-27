package com.nfctransfer.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.nfctransfer.app.util.PermissionHelper;

public class MainActivity extends AppCompatActivity {

    private Button btnSend;
    private Button btnReceive;
    private Button btnHistory;
    private TextView tvNfcStatus;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                updateNfcStatus();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnSend    = findViewById(R.id.btn_send);
        btnReceive = findViewById(R.id.btn_receive);
        btnHistory = findViewById(R.id.btn_history);
        tvNfcStatus = findViewById(R.id.tv_nfc_status);

        btnSend.setOnClickListener(v -> startActivity(new Intent(this, SendActivity.class)));
        btnReceive.setOnClickListener(v -> startActivity(new Intent(this, ReceiveActivity.class)));
        btnHistory.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));

        if (savedInstanceState == null) {
            String[] perms = PermissionHelper.getRequiredPermissions();
            permissionLauncher.launch(perms);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateNfcStatus();
    }

    private void updateNfcStatus() {
        if (!PermissionHelper.isNfcEnabled(this)) {
            tvNfcStatus.setText("NFC 未開啟 — 點此前往設定");
            tvNfcStatus.setOnClickListener(v -> PermissionHelper.showNfcEnableDialog(this));
        } else {
            tvNfcStatus.setText("NFC 已開啟");
            tvNfcStatus.setOnClickListener(null);
        }
    }
}
