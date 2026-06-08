package com.kisslink.pairing;

import android.os.Build;

import androidx.annotation.NonNull;

/**
 * App 範圍的本機配對 token 單一來源。
 *
 * <p>為什麼需要它?碰觸配對可能從<b>任何前景畫面</b>發生(主畫面、傳輸畫面…),
 * 而 NFC 對外當標籤(HCE)需要一份 token,GO 選舉也要用<b>同一份</b> token。
 * 若各畫面各自產生 token,會出現「HCE 廣播的 token ≠ Coordinator 選舉用的 token」,
 * 或主畫面根本沒設 token(HCE 無 NDEF → 對方讀不到 → 一直震動)。
 *
 * <p>因此把本機 token 收斂到這裡:任何畫面的 {@link com.kisslink.nfc.NfcForegroundHelper}
 * 都用 {@link #current()} 設 HCE,Service 的 {@link PairingCoordinator} 也用 {@link #current()}。
 * token 在 App 進程生命週期內保持穩定(nonce 跨「不重疊」的連續場次重用無妨),
 * 確保「主畫面讀到的對方 token」與「對方 Coordinator 廣播的 token」一致。
 */
public final class LocalPairing {

    private static volatile PairingToken token;

    private LocalPairing() {}

    /** 取得本機目前 token(首次呼叫時產生)。 */
    @NonNull
    public static synchronized PairingToken current() {
        if (token == null) token = PairingToken.create(Build.MODEL);
        return token;
    }

    /** 強制換一份新 token(目前未使用;保留給未來需要輪替 nonce 的情境)。 */
    @NonNull
    public static synchronized PairingToken renew() {
        token = PairingToken.create(Build.MODEL);
        return token;
    }
}
