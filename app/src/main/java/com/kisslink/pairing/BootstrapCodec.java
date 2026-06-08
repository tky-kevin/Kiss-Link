package com.kisslink.pairing;

import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * NDEF 訊息的建/解碼——把 {@link PairingToken} 包成一則 NDEF 訊息,
 * 由 HCE(NFC Forum Type-4 tag)對外吐出,或由 reader 解析。
 *
 * <h3>訊息內容</h3>
 * <ol>
 *   <li><b>URI record</b>:{@code kisslink://pair?...}——
 *       既給 OS 冷啟動 dispatch(情境 1,經 deep link intent-filter 啟動 App),
 *       也給本 App reader 直接解析出 token。</li>
 *   <li><b>AAR(Android Application Record)</b>:{@code com.kisslink}——
 *       讓 OS 確定性選到本 App(未安裝則導去 Play 商店)。</li>
 * </ol>
 */
public final class BootstrapCodec {

    public static final String APP_PACKAGE = "com.kisslink";

    private BootstrapCodec() {}

    /** 建立 HCE 對外吐出的 NDEF 訊息（URI + AAR），AAR 使用指定 packageName。 */
    public static NdefMessage buildNdef(@NonNull PairingToken token, @NonNull String packageName) {
        NdefRecord uri = NdefRecord.createUri(token.toUri());
        NdefRecord aar = NdefRecord.createApplicationRecord(packageName);
        return new NdefMessage(new NdefRecord[]{ uri, aar });
    }

    /** @deprecated 改用 {@link #buildNdef(PairingToken, String)}，傳入執行期 packageName。 */
    @Deprecated
    public static NdefMessage buildNdef(@NonNull PairingToken token) {
        return buildNdef(token, APP_PACKAGE);
    }

    /** 取 NDEF 訊息的原始 bytes（供 Type-4 READ BINARY 回應），AAR 使用指定 packageName。 */
    public static byte[] buildNdefBytes(@NonNull PairingToken token, @NonNull String packageName) {
        return buildNdef(token, packageName).toByteArray();
    }

    /** @deprecated 改用 {@link #buildNdefBytes(PairingToken, String)}，傳入執行期 packageName。 */
    @Deprecated
    public static byte[] buildNdefBytes(@NonNull PairingToken token) {
        return buildNdef(token).toByteArray();
    }

    /** 從 NDEF 原始 bytes 解析出 token(reader 相位讀到對方標籤時用)。 */
    @Nullable
    public static PairingToken parseNdef(@Nullable byte[] ndefBytes) {
        if (ndefBytes == null) return null;
        try {
            NdefMessage msg = new NdefMessage(ndefBytes);
            for (NdefRecord r : msg.getRecords()) {
                PairingToken t = parseRecord(r);
                if (t != null) return t;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /** 從已解析的 {@link NdefMessage} 找出 token(供 reader-mode Ndef tech 路徑)。 */
    @Nullable
    public static PairingToken parseNdef(@Nullable NdefMessage msg) {
        if (msg == null) return null;
        for (NdefRecord r : msg.getRecords()) {
            PairingToken t = parseRecord(r);
            if (t != null) return t;
        }
        return null;
    }

    @Nullable
    private static PairingToken parseRecord(@NonNull NdefRecord r) {
        try {
            Uri u = r.toUri(); // URI / well-known U / absolute-URI record 皆可
            return PairingToken.fromUri(u);
        } catch (Exception e) {
            return null;
        }
    }
}
