package com.kisslink.ui.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.kisslink.R;
import com.kisslink.pairing.LocalPairing;
import com.kisslink.pairing.NfcPairingController;
import com.kisslink.pairing.PairingToken;
import com.kisslink.profile.Profile;
import com.kisslink.profile.ProfileStore;
import com.kisslink.transfer.FileTransferService;
import com.kisslink.transfer.SendItem;
import com.kisslink.transfer.SessionState;
import com.kisslink.transfer.TransferProgress;
import com.kisslink.transfer.TransferProtocol;
import com.kisslink.ui.history.HistorySheet;
import com.kisslink.ui.profile.ProfileCardSheet;
import com.kisslink.ui.profile.ReceivedCardSheet;
import com.kisslink.util.PermissionHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 單頁主畫面（C 方案 Beam）——配對、連線、傳輸全在這一頁，靠 {@link SessionState} 切換內容。
 *
 * <p>NFC 配對沿用 {@link NfcPairingController}（reader/HCE，與舊 PairingActivity 同機制），
 * latch 後經 binder 餵入 Service 的 PairingCoordinator；連線/傳輸狀態由單一 SessionState 驅動 UI。
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class HomeActivity extends AppCompatActivity implements ProfileCardSheet.Host {

    private static final String TAG = "HomeActivity";
    private static final long STAGE_MIN_DWELL_MS = 650; // 連線階段每格最短停留

    // ── Views ──
    private BeamStageView beam;
    private TextView   tvHeadline, tvSub, tvPercent, tvReceived;
    private LinearLayout percentRow, receivedBanner;
    private RecyclerView rvItems;
    private MaterialButton btnPickFiles, btnPickMedia, btnSend, btnViewReceived;
    private ImageButton ibHistory;
    private ShapeableImageView ivAvatar;

    private SendListAdapter itemsAdapter;

    // ── Service ──
    @Nullable private FileTransferService.TransferBinder binder;
    private boolean bound = false;

    // ── NFC ──
    @Nullable private NfcPairingController nfc;
    private boolean resumed = false;

    // ── 選取 / 傳輸狀態 ──
    private final List<SendItem> selection = new ArrayList<>();
    private final Set<String> outgoingNames = new HashSet<>();
    private int outgoingRemaining = 0;          // 本批還沒傳完的件數，歸零即清空待傳清單
    private long recvBatchId = 0;               // 目前接收批次
    private int  recvCount = 0;                 // 目前接收批次已完成件數
    private SessionState.Phase lastPhase = SessionState.Phase.IDLE;
    private final Handler main = new Handler(Looper.getMainLooper());

    // 連線階段序列器（NFC→BLE→Wi-Fi，每格最短停留）
    private int stageShown = -1, stageTarget = 0;
    private boolean stageRunning = false;

    // ── 內容選擇器 ──
    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris == null || uris.isEmpty()) return;
                for (Uri uri : uris) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (SecurityException ignored) {}
                    selection.add(SendItem.fromUri(getContentResolver(), uri, TransferProtocol.ITEM_FILE));
                }
                onSelectionChanged();
            });

    private final ActivityResultLauncher<PickVisualMediaRequest> mediaPicker =
            registerForActivityResult(new ActivityResultContracts.PickMultipleVisualMedia(30), uris -> {
                if (uris == null || uris.isEmpty()) return;
                for (Uri uri : uris) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (SecurityException ignored) {}
                    selection.add(SendItem.fromUri(getContentResolver(), uri, TransferProtocol.ITEM_PHOTO));
                }
                onSelectionChanged();
            });

    // ══════════════════════════════════════════════════════════
    //  生命週期
    // ══════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        bindViews();
        applyInsets();

        // 名片姓名 → 對外配對顯示名稱
        LocalPairing.setDisplayName(ProfileStore.get(this).name());

        if (!PermissionHelper.hasPermissions(this)) {
            PermissionHelper.requestPermissions(this);
        }

        // 啟動並綁定傳輸 Service（單一 session 擁有者）
        Intent svc = FileTransferService.intent(this);
        startForegroundService(svc);
        bindService(svc, connection, BIND_AUTO_CREATE);

        renderReady();
    }

    private void bindViews() {
        beam        = findViewById(R.id.beam);
        tvHeadline  = findViewById(R.id.tvHeadline);
        tvSub       = findViewById(R.id.tvSub);
        tvPercent   = findViewById(R.id.tvPercent);
        percentRow  = findViewById(R.id.percentRow);
        rvItems     = findViewById(R.id.rvItems);
        btnPickFiles= findViewById(R.id.btnPickFiles);
        btnPickMedia= findViewById(R.id.btnPickMedia);
        btnSend     = findViewById(R.id.btnSend);
        ibHistory   = findViewById(R.id.ibHistory);
        ivAvatar    = findViewById(R.id.ivAvatar);
        tvReceived     = findViewById(R.id.tvReceived);
        receivedBanner = findViewById(R.id.receivedBanner);
        btnViewReceived= findViewById(R.id.btnViewReceived);

        itemsAdapter = new SendListAdapter();
        rvItems.setLayoutManager(new LinearLayoutManager(this));
        rvItems.setAdapter(itemsAdapter);
        rvItems.setNestedScrollingEnabled(true);
        rvItems.setLayoutAnimation(android.view.animation.AnimationUtils
                .loadLayoutAnimation(this, R.anim.layout_anim_fade_up));
        itemsAdapter.setOnRemove(this::removeSelection);

        btnViewReceived.setOnClickListener(v -> {
            if (recvBatchId != 0)
                HistorySheet.forBatch(recvBatchId).show(getSupportFragmentManager(), "batch");
        });

        btnPickFiles.setOnClickListener(v -> {
            if (!ensurePerms()) return;
            filePicker.launch(new String[]{"*/*"});
        });
        btnPickMedia.setOnClickListener(v -> {
            if (!ensurePerms()) return;
            mediaPicker.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE)
                    .build());
        });
        btnSend.setOnClickListener(v -> doSend());

        ibHistory.setOnClickListener(v ->
                new HistorySheet().show(getSupportFragmentManager(), "history"));
        ivAvatar.setOnClickListener(v ->
                ProfileCardSheet.newInstance().show(getSupportFragmentManager(), "profile"));

        refreshAvatar();
    }

    private void applyInsets() {
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        refreshAvatar();
        // 名片可能在 sheet 內被編輯 → 同步顯示名稱
        LocalPairing.setDisplayName(ProfileStore.get(this).name());
        enableNfcIfReady();
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        if (nfc != null) nfc.disable();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (nfc != null) nfc.handleIntent(intent);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (bound) { unbindService(connection); bound = false; }
        // 不在此 stopService：配對中 Activity 可能重建；服務由 onTaskRemoved / 閒置自動結束。
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (!PermissionHelper.allGranted(grantResults)) {
            toast(getString(R.string.need_perms));
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Service 綁定
    // ══════════════════════════════════════════════════════════

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            binder = (FileTransferService.TransferBinder) service;
            bound = true;
            enableNfcIfReady();
            binder.getSessionState().observe(HomeActivity.this, HomeActivity.this::onSession);
            binder.getIncomingCard().observe(HomeActivity.this, vcard -> {
                if (vcard == null || vcard.length == 0) return;
                ReceivedCardSheet.newInstance(vcard).show(getSupportFragmentManager(), "received_card");
                binder.clearIncomingCard();
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false; binder = null;
        }
    };

    // ══════════════════════════════════════════════════════════
    //  NFC
    // ══════════════════════════════════════════════════════════

    private void ensureController() {
        if (nfc != null) return;
        nfc = new NfcPairingController(this, new NfcPairingController.Callback() {
            @Override public void onPeerToken(@NonNull PairingToken peer) {
                haptic();
                if (binder != null) binder.onNfcLatchedAsReader(peer);
            }
            @Override public void onTagRead() {
                haptic();
                if (binder != null) binder.onNfcLatchedAsTag();
            }
            @Override public void onError(@NonNull String message) { toast(message); }
        });
    }

    private void enableNfcIfReady() {
        if (!bound || binder == null || !resumed) return;
        ensureController();
        nfc.setLocalToken(binder.localToken());
        nfc.enable();
        nfc.handleIntent(getIntent());
    }

    // ══════════════════════════════════════════════════════════
    //  狀態 → UI
    // ══════════════════════════════════════════════════════════

    private void onSession(SessionState st) {
        SessionState.Phase p = st.phase;

        switch (p) {
            case IDLE:
            case CANCELLED:
                stopStageTicker();
                renderReady();
                if (nfc != null && lastPhase != SessionState.Phase.IDLE) nfc.resetLatched();
                break;

            case RESETTING:
                stopStageTicker();
                beam.setPhase(BeamStageView.CONNECTING);
                showHeadlineText(getString(R.string.home_resetting_title), "");
                break;

            case PAIRING_LATCHED:
            case PAIRING_LINKING:
            case PAIRING_ELECTING:
            case CREATING_GROUP:
            case HOSTING:
            case CONNECTING:
                beam.setPhase(BeamStageView.CONNECTING);
                showHeadlineText(getString(R.string.home_connecting_title), null);
                runStageTicker(p);
                break;

            case CONNECTED:
                stopStageTicker();
                beam.setPhase(BeamStageView.CONNECTED);
                showPeerIdentity();
                showHeadlineText(getString(R.string.home_connected_title), peerSubLabel());
                if (nfc != null) nfc.resetLatched(); // 連上後仍可再碰（同對象 resume / 新對象切換）
                updateSendButton();
                rebuildIdleRows();
                break;

            case TRANSFERRING:
                stopStageTicker();
                onTransferring(st.progress);
                break;

            case FILE_DONE:
            case ALL_DONE:
                onTransferDone(st.progress, p == SessionState.Phase.ALL_DONE);
                break;

            case ERROR:
                stopStageTicker();
                beam.setPhase(BeamStageView.ERROR);
                showHeadlineText("連線中斷", st.error != null ? st.error : "請再碰一下重試");
                if (nfc != null) nfc.resetLatched();
                break;

            default: break;
        }
        lastPhase = p;
    }

    // ── READY ──
    private void renderReady() {
        beam.setPhase(BeamStageView.READY);
        beam.setSelfIdentity(ProfileStore.get(this).name());
        beam.setSelfAvatar(ProfileStore.get(this).loadAvatar());
        beam.setPeerIdentity(null);
        beam.setPeerAvatar(null);
        showHeadlineText(getString(R.string.home_ready_title), getString(R.string.home_ready_sub));
        hideReceivedBanner();
        recvBatchId = 0; recvCount = 0;
        updateSendButton();
        rebuildIdleRows();
    }

    // ── 連線階段序列器 ──
    private void runStageTicker(SessionState.Phase p) {
        int target;
        switch (p) {
            case PAIRING_LATCHED: target = 0; break;          // NFC
            case PAIRING_LINKING:
            case PAIRING_ELECTING: target = 1; break;          // BLE
            default: target = 2; break;                        // Wi-Fi
        }
        stageTarget = Math.max(stageTarget, target);
        if (!stageRunning) {
            stageRunning = true;
            stageShown = -1;
            main.post(stageTick);
        }
    }

    private final Runnable stageTick = new Runnable() {
        @Override public void run() {
            if (!stageRunning) return;
            if (stageShown < stageTarget) {
                stageShown++;
                tvSub.setText(stageLabel(stageShown));
            }
            main.postDelayed(this, STAGE_MIN_DWELL_MS);
        }
    };

    private void stopStageTicker() {
        stageRunning = false;
        stageTarget = 0;
        stageShown = -1;
        main.removeCallbacks(stageTick);
    }

    private String stageLabel(int step) {
        switch (step) {
            case 0:  return getString(R.string.stage_nfc);
            case 1:  return getString(R.string.stage_ble);
            default: return getString(R.string.stage_wifi);
        }
    }

    // ── 連線對象身份 ──
    private void showPeerIdentity() {
        String peerName = binder != null ? binder.connectedPeerName() : null;
        beam.setPeerIdentity(peerName);
        byte[] avatar = binder != null ? binder.connectedPeerAvatar() : null;
        beam.setPeerAvatar(avatar != null ? decodeAvatar(avatar) : null);
        beam.setSelfAvatar(ProfileStore.get(this).loadAvatar());
        beam.setSelfIdentity(ProfileStore.get(this).name());
    }

    @Nullable
    private static Bitmap decodeAvatar(byte[] bytes) {
        try { return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length); }
        catch (Exception e) { return null; }
    }

    private String peerSubLabel() {
        String peerName = binder != null ? binder.connectedPeerName() : null;
        return peerName != null ? peerName : "可互傳檔案、相片與名片";
    }

    // ── 傳輸中 ──
    private void onTransferring(@Nullable TransferProgress tp) {
        if (tp == null) return;
        boolean outgoing = tp.outgoing;
        beam.setDirection(outgoing ? BeamStageView.SEND : BeamStageView.RECEIVE);
        beam.setPhase(BeamStageView.TRANSFERRING);
        int pct = tp.percentInt();
        beam.setProgress(pct >= 0 ? pct / 100f : 0f);
        showPercent(pct);
        tvSub.setText((outgoing ? getString(R.string.sending) : getString(R.string.receiving))
                + " · " + tp.fileName);
        if (outgoing) {
            if (tp.itemType != TransferProtocol.ITEM_VCARD) updateOutgoingRows(tp, false);
        } else {
            // 接收新批次 → 重置橫幅計數
            if (tp.batchId != recvBatchId) { recvBatchId = tp.batchId; recvCount = 0; hideReceivedBanner(); }
        }
    }

    private void onTransferDone(@Nullable TransferProgress tp, boolean all) {
        beam.setPhase(BeamStageView.DONE);
        beam.setProgress(1f);
        boolean outgoing = tp != null && tp.outgoing;
        hidePercent();
        String peer = binder != null ? binder.connectedPeerName() : null;
        String who = peer != null ? peer : "對方";
        showHeadlineText(getString(R.string.home_connected_title),
                outgoing ? getString(R.string.sent_to, who) : getString(R.string.received_from, who));

        if (outgoing && tp != null && tp.itemType == TransferProtocol.ITEM_VCARD) {
            // 名片獨立傳送，不影響待傳清單
        } else if (outgoing) {
            updateOutgoingRows(tp, true);
            if (outgoingRemaining > 0) outgoingRemaining--;
            if (outgoingRemaining <= 0 && !selection.isEmpty()) {
                // 整批傳完 → 清空待傳清單
                selection.clear();
                outgoingNames.clear();
                rebuildIdleRows();
            }
        } else if (tp != null) {
            if (tp.batchId != recvBatchId) { recvBatchId = tp.batchId; recvCount = 0; }
            recvCount++;
            showReceivedBanner(recvCount);
        }

        // 短暫顯示完成後回到「已連線」可再選
        main.postDelayed(() -> {
            if ((lastPhase == SessionState.Phase.ALL_DONE || lastPhase == SessionState.Phase.FILE_DONE)
                    && isConnected()) {
                beam.setPhase(BeamStageView.CONNECTED);
            }
        }, 1400);
        updateSendButton();
    }

    private void showReceivedBanner(int count) {
        tvReceived.setText(getString(R.string.received_batch, count));
        Anim.revealFadeUp(receivedBanner);
    }

    private void hideReceivedBanner() {
        receivedBanner.setVisibility(View.GONE);
    }

    // ── 內容選擇 / 傳送 ──
    private void onSelectionChanged() {
        rebuildIdleRows();
        updateSendButton();
        if (isConnected() && !selection.isEmpty()) {
            tvSub.setText(selection.size() + " 個項目待傳送");
        } else if (!selection.isEmpty()) {
            tvSub.setText(selection.size() + " 個項目 · " + getString(R.string.tap_to_connect_first));
        }
    }

    private void removeSelection(int position) {
        if (position < 0 || position >= selection.size()) return;
        selection.remove(position);
        onSelectionChanged();
    }

    private void doSend() {
        if (binder == null || !isConnected() || selection.isEmpty()) return;
        outgoingNames.clear();
        for (SendItem it : selection) outgoingNames.add(it.name);
        outgoingRemaining = selection.size();
        binder.sendItems(new ArrayList<>(selection));
        haptic();
    }

    private void updateSendButton() {
        boolean show = isConnected() && !selection.isEmpty()
                && lastPhase != SessionState.Phase.TRANSFERRING;
        if (show) {
            btnSend.setText(getString(R.string.btn_send_n, selection.size()));
            Anim.revealFadeUp(btnSend);
        } else {
            btnSend.setVisibility(View.GONE);
        }
    }

    private boolean isConnected() {
        return lastPhase == SessionState.Phase.CONNECTED
                || lastPhase == SessionState.Phase.TRANSFERRING
                || lastPhase == SessionState.Phase.FILE_DONE
                || lastPhase == SessionState.Phase.ALL_DONE;
    }

    // ── 清單 ──
    private void rebuildIdleRows() {
        List<SendRow> rows = new ArrayList<>();
        for (SendItem it : selection) {
            SendRow r = new SendRow(it.name, sizeLabel(it.size), it.itemType,
                    it.itemType == TransferProtocol.ITEM_PHOTO ? it.uri : null);
            r.removable = true;
            rows.add(r);
        }
        itemsAdapter.submit(rows);
        if (!rows.isEmpty()) rvItems.scheduleLayoutAnimation();
    }

    /** 傳送中：以待傳清單為基底，更新對應列的進度/完成（接收端改用收到橫幅，不進清單）。 */
    private void updateOutgoingRows(@NonNull TransferProgress tp, boolean done) {
        List<SendRow> rows = new ArrayList<>();
        for (SendItem it : selection) {
            SendRow r = new SendRow(it.name, sizeLabel(it.size), it.itemType,
                    it.itemType == TransferProtocol.ITEM_PHOTO ? it.uri : null);
            if (it.name.equals(tp.fileName)) {
                r.percent = done ? 100 : tp.percentInt();
                r.done = done;
            }
            rows.add(r);
        }
        itemsAdapter.submit(rows);
    }

    // ── headline / percent ──
    private void showHeadlineText(String title, @Nullable String sub) {
        hidePercent();
        tvHeadline.setVisibility(View.VISIBLE);
        boolean changed = !title.contentEquals(tvHeadline.getText());
        tvHeadline.setText(title);
        if (changed) Anim.fadeUp(tvHeadline);
        if (sub != null) {
            boolean subChanged = !sub.contentEquals(tvSub.getText());
            tvSub.setText(sub);
            if (subChanged) Anim.fadeUp(tvSub);
        }
    }

    private void showPercent(int pct) {
        tvHeadline.setVisibility(View.INVISIBLE);
        percentRow.setVisibility(View.VISIBLE);
        tvPercent.setText(pct >= 0 ? String.valueOf(pct) : "0");
    }

    private void hidePercent() {
        percentRow.setVisibility(View.GONE);
        tvHeadline.setVisibility(View.VISIBLE);
    }

    // ── 頭像 ──
    private void refreshAvatar() {
        Bitmap a = ProfileStore.get(this).loadAvatar();
        if (a != null) {
            ivAvatar.setPadding(0, 0, 0, 0);
            ivAvatar.setImageBitmap(a);
        } else {
            int pad = Math.round(7 * getResources().getDisplayMetrics().density);
            ivAvatar.setPadding(pad, pad, pad, pad);
            ivAvatar.setImageResource(R.drawable.ic_avatar_default);
        }
    }

    // ── 工具 ──
    private boolean ensurePerms() {
        if (PermissionHelper.hasPermissions(this)) return true;
        PermissionHelper.requestPermissions(this);
        return false;
    }

    private void haptic() {
        if (beam != null) beam.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
    }

    private static String sizeLabel(long bytes) {
        if (bytes < 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public static Intent intent(Context ctx) {
        return new Intent(ctx, HomeActivity.class);
    }

    // ══════════════════════════════════════════════════════════
    //  ProfileCardSheet.Host
    // ══════════════════════════════════════════════════════════

    @Override
    public void onProfileChanged() {
        refreshAvatar();
        ProfileStore ps = ProfileStore.get(this);
        LocalPairing.setDisplayName(ps.name());
        beam.setSelfIdentity(ps.name());
        beam.setSelfAvatar(ps.loadAvatar());
        if (nfc != null && binder != null) nfc.setLocalToken(binder.localToken());
    }

    @Override
    public void sendMyProfileCard() {
        if (binder == null || !isConnected()) {
            toast(getString(R.string.tap_to_connect_first));
            return;
        }
        ProfileStore ps = ProfileStore.get(this);
        Profile p = ps.load();
        String fileName = getString(R.string.card_of, ps.name());
        SendItem card = SendItem.vcard(fileName, p.toVCard());
        List<SendItem> one = new ArrayList<>();
        one.add(card);
        // 名片獨立傳送，不動待傳清單（不計入 outgoingRemaining）
        binder.sendItems(one);
        haptic();
    }
}
