package com.kisslink.ui.transfer;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.kisslink.transfer.FileTransferService;
import com.kisslink.transfer.TransferProgress;

import java.util.List;

/**
 * TransferActivity 的 ViewModel，負責與 {@link FileTransferService} 通訊。
 *
 * <p>透過 Service Binding 取得 LiveData，即使 Activity 重建（螢幕旋轉）
 * 也能持續觀察傳輸進度。
 */
public class TransferViewModel extends AndroidViewModel {

    private static final String TAG = "TransferViewModel";

    private FileTransferService.TransferBinder serviceBinder;
    private boolean bound = false;

    // 中繼 LiveData，確保未綁定前也能安全觀察
    private final MutableLiveData<TransferProgress> progressLd =
            new MutableLiveData<>(TransferProgress.waiting());

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            serviceBinder = (FileTransferService.TransferBinder) service;
            bound = true;
            Log.d(TAG, "Service bound");
            // getProgress() 永遠非 null（服務層 serviceLd 在 Service 建立時即初始化）
            serviceBinder.getProgress()
                    .observeForever(p -> { if (p != null) progressLd.postValue(p); });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            serviceBinder = null;
            Log.d(TAG, "Service disconnected");
        }
    };

    public TransferViewModel(@NonNull Application application) {
        super(application);
    }

    // ── Service 綁定 ──────────────────────────────────────────

    public void bindService(Context ctx) {
        Intent intent = new Intent(ctx, FileTransferService.class);
        ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    public void unbindService(Context ctx) {
        if (bound) {
            ctx.unbindService(connection);
            bound = false;
        }
    }

    // ── 傳送方 API ────────────────────────────────────────────

    /** 傳送方：傳入使用者選擇的檔案 URIs，開始傳輸。 */
    public void sendFiles(List<Uri> uris) {
        if (serviceBinder != null) {
            serviceBinder.sendFiles(uris);
        } else {
            Log.w(TAG, "Service not bound, cannot send");
        }
    }

    /** 取消傳輸。 */
    public void cancel() {
        if (serviceBinder != null) serviceBinder.cancel();
    }

    // ── LiveData ──────────────────────────────────────────────

    public LiveData<TransferProgress> getProgress() { return progressLd; }

    @Override
    protected void onCleared() {
        super.onCleared();
        // ViewModel 銷毀時不解綁（由 Activity 在 onStop 解綁）
    }
}
