package com.kisslink.ui.pairing;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.snackbar.Snackbar;
import com.kisslink.R;
import com.kisslink.model.GroupCredential;
import com.kisslink.nfc.NFCManager;
import com.kisslink.transfer.FileTransferService;
import com.kisslink.ui.transfer.TransferActivity;
import com.kisslink.wifidirect.ConnectionState;

import java.util.ArrayList;

/**
 * NFC 碰觸等待畫面（傳送方 / 接收方共用）。
 *
 * <ul>
 *   <li><b>SENDER</b>：持有從 MainActivity 傳入的檔案 URIs，
 *       建立 Wi-Fi Direct Group + 啟動 HCE，等待對方碰觸。
 *       連線後將 URIs 帶入 {@link TransferActivity} 立即開始傳送。</li>
 *   <li><b>RECEIVER</b>：啟用 NFC Reader 等待碰觸，
 *       讀到憑證後靜默連線（{@link com.kisslink.wifidirect.WifiDirectManager#connectAsClient}）。</li>
 * </ul>
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class PairingActivity extends AppCompatActivity {

    private static final String EXTRA_ROLE = "role";
    private static final String EXTRA_URIS = "uris";   // ArrayList<Uri>

    // ── Views ─────────────────────────────────────────────────
    private TextView tvRole, tvStatus, tvHint;
    private Button   btnCancel;
    private View     nfcAnimView;

    // ── ViewModel & NFC ───────────────────────────────────────
    private PairingViewModel viewModel;
    private NFCManager       nfcManager;

    // ══════════════════════════════════════════════════════════
    //  Factory
    // ══════════════════════════════════════════════════════════

    /**
     * @param uris 傳送方選好的檔案 URIs（SENDER 必填；RECEIVER 傳 null）
     */
    public static Intent newIntent(Context ctx,
                                   PairingViewModel.Role role,
                                   @Nullable ArrayList<Uri> uris) {
        Intent i = new Intent(ctx, PairingActivity.class);
        i.putExtra(EXTRA_ROLE, role.name());
        if (uris != null && !uris.isEmpty()) {
            i.putParcelableArrayListExtra(EXTRA_URIS, uris);
        }
        return i;
    }

    // ══════════════════════════════════════════════════════════
    //  生命週期
    // ══════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pairing);
        bindViews();

        PairingViewModel.Role role = PairingViewModel.Role.valueOf(
                getIntent().getStringExtra(EXTRA_ROLE));

        viewModel = new ViewModelProvider(this).get(PairingViewModel.class);
        nfcManager = new NFCManager(this);

        viewModel.init(role, this);
        setupUiForRole(role);
        observeViewModel();

        btnCancel.setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.registerReceiver(this);

        // RECEIVER：啟用 NFC Reader 模式
        if (viewModel.getRole() == PairingViewModel.Role.RECEIVER) {
            nfcManager.enableReaderMode(
                    this,
                    cred -> runOnUiThread(() -> viewModel.onNfcCredentialReceived(cred)),
                    err  -> runOnUiThread(() -> showError(err)));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        viewModel.unregisterReceiver(this);
        nfcManager.disableReaderMode(this);
    }

    // ══════════════════════════════════════════════════════════
    //  UI 初始化
    // ══════════════════════════════════════════════════════════

    private void bindViews() {
        tvRole      = findViewById(R.id.tvRole);
        tvStatus    = findViewById(R.id.tvStatus);
        tvHint      = findViewById(R.id.tvHint);
        btnCancel   = findViewById(R.id.btnCancel);
        nfcAnimView = findViewById(R.id.nfcAnimView);
    }

    private void setupUiForRole(PairingViewModel.Role role) {
        if (role == PairingViewModel.Role.SENDER) {
            tvRole.setText("傳送方");
            tvStatus.setText("正在建立連線…");
            tvHint.setText("等待對方靠近並碰觸");

            // 顯示已選幾個檔案
            ArrayList<Uri> uris = getIntent().getParcelableArrayListExtra(EXTRA_URIS);
            if (uris != null && !uris.isEmpty()) {
                tvHint.setText("已選 " + uris.size() + " 個檔案，等待碰觸後立即傳送");
            }
        } else {
            tvRole.setText("接收方");
            tvStatus.setText("請靠近傳送方手機");
            tvHint.setText("輕碰兩台手機 NFC 感應區");
        }
    }

    // ══════════════════════════════════════════════════════════
    //  LiveData 觀察
    // ══════════════════════════════════════════════════════════

    private void observeViewModel() {
        viewModel.getConnectionState().observe(this, state -> {
            updateStatusText(state);
            if (state == ConnectionState.CONNECTED) onConnectionEstablished();
        });

        viewModel.getError().observe(this, err -> {
            if (err != null && !err.isEmpty()) showError(err);
        });
    }

    private void updateStatusText(ConnectionState state) {
        switch (state) {
            case CREATING_GROUP: tvStatus.setText("建立 Wi-Fi Direct 群組…");  break;
            case HOSTING:        tvStatus.setText("等待對方碰觸 NFC…");          break;
            case CONNECTING:     tvStatus.setText("靜默連線中，請稍候…");         break;
            case CONNECTED:      tvStatus.setText("連線成功！");                  break;
            case ERROR:          tvStatus.setText("連線失敗，請重試");             break;
            default: break;
        }
    }

    // ══════════════════════════════════════════════════════════
    //  連線成功
    // ══════════════════════════════════════════════════════════

    private void onConnectionEstablished() {
        GroupCredential cred = viewModel.getLastCredential();
        PairingViewModel.Role role = viewModel.getRole();

        // 1. 啟動前景 Service（傳輸通知 + 保持 Wi-Fi 存活）
        if (cred != null) {
            Intent svcIntent = (role == PairingViewModel.Role.SENDER)
                    ? FileTransferService.senderIntent(this, cred)
                    : FileTransferService.receiverIntent(this, cred);
            startForegroundService(svcIntent);
        }

        // 2. 帶著 URIs 跳轉到傳輸畫面
        ArrayList<Uri> uris = getIntent().getParcelableArrayListExtra(EXTRA_URIS);
        Intent i = TransferActivity.newIntent(
                this,
                role == PairingViewModel.Role.SENDER
                        ? TransferActivity.ROLE_SENDER
                        : TransferActivity.ROLE_RECEIVER,
                uris);
        startActivity(i);
        finish();
    }

    private void showError(String msg) {
        Snackbar.make(btnCancel, msg, Snackbar.LENGTH_LONG).show();
    }
}
