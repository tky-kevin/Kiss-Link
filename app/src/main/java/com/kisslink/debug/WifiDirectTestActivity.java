package com.kisslink.debug;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.lifecycle.Observer;

import com.kisslink.R;
import com.kisslink.model.GroupCredential;
import com.kisslink.wifidirect.ConnectionState;
import com.kisslink.wifidirect.WifiDirectManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Wi-Fi Direct 手動測試 Activity（開發除錯用）。
 *
 * <h3>單機測試（GO 端）</h3>
 * <ol>
 *   <li>點「建立群組」→ 等待狀態變為 HOSTING</li>
 *   <li>觀察畫面上出現的 SSID / Passphrase / IP</li>
 *   <li>Log 中應出現「GroupCredential ready」</li>
 *   <li>點「解散群組」→ 狀態應回到 DISCONNECTED</li>
 * </ol>
 *
 * <h3>雙機測試</h3>
 * <ol>
 *   <li>手機 A（GO）：點「建立群組」→ 記錄 SSID / Passphrase</li>
 *   <li>手機 B（Client）：將 SSID / Passphrase 填入欄位 → 點「連線至 GO」</li>
 *   <li>B 狀態應依序：IDLE → CONNECTING → CONNECTED</li>
 *   <li>A 狀態應從 HOSTING → CONNECTED（需配合 M2 TransferServer 才完整觸發）</li>
 * </ol>
 *
 * <p>加入 AndroidManifest.xml：
 * <pre>{@code
 * <activity android:name=".debug.WifiDirectTestActivity"
 *     android:exported="true"/>
 * }</pre>
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class WifiDirectTestActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 100;

    // ── 權限清單 ──────────────────────────────────────────────
    private static final String[] REQUIRED_PERMISSIONS =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? new String[]{
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.ACCESS_FINE_LOCATION
            }
                    : new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE
            };

    // ── Views ─────────────────────────────────────────────────
    private TextView    tvState;
    private TextView    tvCredential;
    private TextView    tvLog;
    private ScrollView  scrollLog;
    private EditText    etSsid;
    private EditText    etPassphrase;
    private Button      btnCreateGroup;
    private Button      btnRemoveGroup;
    private Button      btnConnect;
    private Button      btnReset;

    // ── 核心 ──────────────────────────────────────────────────
    private WifiDirectManager wifiDirectManager;

    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    // ══════════════════════════════════════════════════════════
    //  生命週期
    // ══════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_direct_test);

        bindViews();
        wifiDirectManager = new WifiDirectManager(this);
        observeLiveData();
        setClickListeners();
        checkAndRequestPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        wifiDirectManager.registerReceiver(this);
        log("▶ onResume — Receiver 已註冊");
    }

    @Override
    protected void onPause() {
        super.onPause();
        wifiDirectManager.unregisterReceiver(this);
        log("⏸ onPause — Receiver 已解除");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        wifiDirectManager.reset();
    }

    // ══════════════════════════════════════════════════════════
    //  UI 初始化
    // ══════════════════════════════════════════════════════════

    private void bindViews() {
        tvState      = findViewById(R.id.tvState);
        tvCredential = findViewById(R.id.tvCredential);
        tvLog        = findViewById(R.id.tvLog);
        scrollLog    = findViewById(R.id.scrollLog);
        etSsid       = findViewById(R.id.etSsid);
        etPassphrase = findViewById(R.id.etPassphrase);
        btnCreateGroup = findViewById(R.id.btnCreateGroup);
        btnRemoveGroup = findViewById(R.id.btnRemoveGroup);
        btnConnect     = findViewById(R.id.btnConnect);
        btnReset       = findViewById(R.id.btnReset);
    }

    private void setClickListeners() {

        btnCreateGroup.setOnClickListener(v -> {
            log("⚡ 點擊「建立群組」");
            wifiDirectManager.createGroupAsGO();
        });

        btnRemoveGroup.setOnClickListener(v -> {
            log("⚡ 點擊「解散群組」");
            wifiDirectManager.removeGroup();
        });

        btnConnect.setOnClickListener(v -> {
            String ssid = etSsid.getText().toString().trim();
            String pass = etPassphrase.getText().toString().trim();

            if (ssid.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "請先填入 SSID 和 Passphrase", Toast.LENGTH_SHORT).show();
                return;
            }

            GroupCredential cred = new GroupCredential(
                    ssid, pass, WifiDirectManager.GO_IP_ADDRESS, WifiDirectManager.TRANSFER_PORT);
            log("⚡ 點擊「連線至 GO」: " + cred);
            wifiDirectManager.connectAsClient(cred);
        });

        btnReset.setOnClickListener(v -> {
            log("⚡ 點擊「重置」");
            wifiDirectManager.reset();
        });
    }

    // ══════════════════════════════════════════════════════════
    //  LiveData 觀察
    // ══════════════════════════════════════════════════════════

    private void observeLiveData() {

        wifiDirectManager.getState().observe(this, state -> {
            tvState.setText(state.name());
            tvState.setTextColor(stateColor(state));
            log("🔄 狀態變更 → " + state);
            updateButtonEnabledState(state);
        });

        wifiDirectManager.getCredential().observe(this, cred -> {
            if (cred == null) return;
            String display = "SSID       : " + cred.getSsid()
                    + "\nPassphrase : " + cred.getPassphrase()
                    + "\nGO IP      : " + cred.getGoIpAddress()
                    + "\nPort       : " + cred.getTransferPort();
            tvCredential.setText(display);
            log("✅ 憑證就緒\n" + display);

            // 自動填入 Client 欄位（方便同機模擬測試）
            etSsid.setText(cred.getSsid());
            etPassphrase.setText(cred.getPassphrase());
        });

        wifiDirectManager.getError().observe(this, error -> {
            if (error == null || error.isEmpty()) return;
            log("❌ 錯誤：" + error);
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
        });
    }

    // ══════════════════════════════════════════════════════════
    //  輔助方法
    // ══════════════════════════════════════════════════════════

    private void updateButtonEnabledState(ConnectionState state) {
        boolean idle = (state == ConnectionState.IDLE);
        btnCreateGroup.setEnabled(idle);
        btnConnect.setEnabled(idle);
        btnRemoveGroup.setEnabled(state == ConnectionState.HOSTING
                || state == ConnectionState.CONNECTED);
        btnReset.setEnabled(state != ConnectionState.IDLE);
    }

    @SuppressLint("SetTextI18n")
    private void log(String message) {
        String line = "[" + timeFormat.format(new Date()) + "] " + message + "\n";
        runOnUiThread(() -> {
            tvLog.append(line);
            // 自動捲到底部
            scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
        });
        android.util.Log.d("WifiDirectTest", message);
    }

    private int stateColor(ConnectionState state) {
        switch (state) {
            case CONNECTED:       return 0xFF2E7D32; // 深綠
            case HOSTING:         return 0xFF1565C0; // 深藍
            case CREATING_GROUP:
            case CONNECTING:      return 0xFFE65100; // 橘色（進行中）
            case ERROR:           return 0xFFB71C1C; // 深紅
            case DISCONNECTED:    return 0xFF546E7A; // 灰藍
            default:              return 0xFF212121; // 黑
        }
    }

    // ══════════════════════════════════════════════════════════
    //  權限處理
    // ══════════════════════════════════════════════════════════

    private void checkAndRequestPermissions() {
        boolean allGranted = true;
        for (String p : REQUIRED_PERMISSIONS) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (!allGranted) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS);
        } else {
            log("✅ 所有權限已取得");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) { allGranted = false; break; }
            }
            if (allGranted) {
                log("✅ 權限請求通過");
            } else {
                log("❌ 部分權限被拒絕，Wi-Fi Direct 可能無法運作");
                Toast.makeText(this, "需要 Wi-Fi 相關權限才能使用此功能", Toast.LENGTH_LONG).show();
            }
        }
    }
}
