package com.kisslink.ui.pairing;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.snackbar.Snackbar;
import com.kisslink.R;
import com.kisslink.model.GroupCredential;
import com.kisslink.nfc.NFCManager;
import com.kisslink.transfer.FileTransferService;
import com.kisslink.transfer.SessionState;
import com.kisslink.ui.transfer.TransferActivity;

import java.util.ArrayList;

/**
 * NFC 碰觸等待畫面（傳送方 / 接收方共用）。
 *
 * <p>本畫面已不再自行持有 Wi-Fi Direct 連線；連線由 {@link FileTransferService} 擁有，
 * 本畫面只負責：
 * <ul>
 *   <li>啟動並綁定 {@link FileTransferService}（帶角色）。</li>
 *   <li>RECEIVER：啟用 NFC Reader，讀到憑證後交給 Service 連線。</li>
 *   <li>觀察連線狀態，{@link ConnectionState#CONNECTED} 後跳轉 {@link TransferActivity}。</li>
 * </ul>
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class PairingActivity extends AppCompatActivity {

    /** 配對角色。 */
    public enum Role { SENDER, RECEIVER }

    private static final String EXTRA_ROLE = "role";
    private static final String EXTRA_URIS = "uris";   // ArrayList<Uri>

    // ── Views ─────────────────────────────────────────────────
    private TextView tvRole, tvStatus, tvHint;
    private Button   btnCancel;

    // ── NFC & Service ─────────────────────────────────────────
    private NFCManager nfcManager;
    private Role       role;

    private FileTransferService.TransferBinder binder;
    private boolean bound          = false;
    private boolean navigating     = false;
    private boolean filesEnqueued  = false; // 傳送方待傳檔案是否已交給 Service（防重複）
    @Nullable private GroupCredential pendingCredential; // NFC 早於 bind 時暫存

    // ══════════════════════════════════════════════════════════
    //  Factory
    // ══════════════════════════════════════════════════════════

    /**
     * @param uris 傳送方選好的檔案 URIs（SENDER 必填；RECEIVER 傳 null）
     */
    public static Intent newIntent(Context ctx, Role role, @Nullable ArrayList<Uri> uris) {
        Intent i = new Intent(ctx, PairingActivity.class);
        i.putExtra(EXTRA_ROLE, role.name());
        if (uris != null && !uris.isEmpty()) {
            i.putParcelableArrayListExtra(EXTRA_URIS, uris);
        }
        return i;
    }

    // ══════════════════════════════════════════════════════════
    //  Service 連線
    // ══════════════════════════════════════════════════════════

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = (FileTransferService.TransferBinder) service;
            bound = true;

            binder.getSessionState().observe(PairingActivity.this, st -> {
                updateStatusText(st);
                // 連線成功（或已直接進入傳輸階段）→ 跳轉傳輸畫面
                if (st.isTransferStartedOrConnected()) goToTransfer();
                else if (st.isError() && st.error != null) showError(st.error);
            });

            // 若 NFC 在綁定前就讀到憑證，補送一次
            if (role == Role.RECEIVER && pendingCredential != null) {
                binder.submitReceiverCredential(pendingCredential);
                pendingCredential = null;
            }

            // 傳送方：一綁定就把待傳檔案交給 Service。實際開送由 Service 在 server 握手後自動觸發，
            // 不再依賴導航到 TransferActivity 的時機（修正接收方卡在「等待傳送方」的問題）。
            if (role == Role.SENDER && !filesEnqueued) {
                ArrayList<Uri> uris = getIntent().getParcelableArrayListExtra(EXTRA_URIS);
                if (uris != null && !uris.isEmpty()) {
                    binder.enqueueFiles(uris);
                    filesEnqueued = true;
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            binder = null;
        }
    };

    // ══════════════════════════════════════════════════════════
    //  生命週期
    // ══════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pairing);
        bindViews();

        role = Role.valueOf(getIntent().getStringExtra(EXTRA_ROLE));
        nfcManager = new NFCManager(this);
        setupUiForRole(role);

        btnCancel.setOnClickListener(v -> { stopSessionService(); finish(); });

        // 啟動並綁定 Service（帶角色）。Service 立即接手連線：
        //  SENDER  → 建立群組 + 立即 listen；RECEIVER → 等 NFC 憑證。
        Intent svc = (role == Role.SENDER)
                ? FileTransferService.senderIntent(this)
                : FileTransferService.receiverIntent(this);
        startForegroundService(svc);
        bindService(svc, connection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (role == Role.RECEIVER) {
            nfcManager.enableReaderMode(
                    this,
                    cred -> runOnUiThread(() -> onCredential(cred)),
                    err  -> runOnUiThread(() -> showError(err)));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (role == Role.RECEIVER) nfcManager.disableReaderMode(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        // 使用者中途離開（非前往傳輸畫面）→ 結束整個 session，避免殘留的群組/前景服務。
        if (isFinishing() && !navigating) stopSessionService();
    }

    // ══════════════════════════════════════════════════════════
    //  NFC 憑證
    // ══════════════════════════════════════════════════════════

    private void onCredential(GroupCredential cred) {
        if (binder != null) {
            binder.submitReceiverCredential(cred);
        } else {
            pendingCredential = cred; // 待 onServiceConnected 補送
        }
    }

    // ══════════════════════════════════════════════════════════
    //  跳轉
    // ══════════════════════════════════════════════════════════

    private void goToTransfer() {
        if (navigating) return;
        navigating = true; // 避免重複觸發，並讓 onDestroy 不要停掉 Service

        ArrayList<Uri> uris = getIntent().getParcelableArrayListExtra(EXTRA_URIS);
        startActivity(TransferActivity.newIntent(
                this,
                role == Role.SENDER ? TransferActivity.ROLE_SENDER : TransferActivity.ROLE_RECEIVER,
                uris));
        finish();
    }

    private void stopSessionService() {
        stopService(new Intent(this, FileTransferService.class));
    }

    // ══════════════════════════════════════════════════════════
    //  UI
    // ══════════════════════════════════════════════════════════

    private void bindViews() {
        tvRole      = findViewById(R.id.tvRole);
        tvStatus    = findViewById(R.id.tvStatus);
        tvHint      = findViewById(R.id.tvHint);
        btnCancel   = findViewById(R.id.btnCancel);
    }

    private void setupUiForRole(Role role) {
        if (role == Role.SENDER) {
            tvRole.setText("傳送方");
            tvStatus.setText("正在建立連線…");
            tvHint.setText("等待對方靠近並碰觸");

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

    private void updateStatusText(SessionState st) {
        switch (st.phase) {
            case CREATING_GROUP: tvStatus.setText("建立 Wi-Fi Direct 群組…");  break;
            case HOSTING:        tvStatus.setText("等待對方碰觸 NFC…");          break;
            case CONNECTING:     tvStatus.setText("靜默連線中，請稍候…");         break;
            case CONNECTED:      tvStatus.setText("連線成功！");                  break;
            case ERROR:          tvStatus.setText("連線失敗，請重試");             break;
            default: break;
        }
    }

    private void showError(String msg) {
        Snackbar.make(btnCancel, msg, Snackbar.LENGTH_LONG).show();
    }
}
