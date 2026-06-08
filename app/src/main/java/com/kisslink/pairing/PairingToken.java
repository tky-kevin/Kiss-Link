package com.kisslink.pairing;

import android.net.Uri;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 碰觸配對的「bootstrap token」——在 NFC latch 當下單向交換的極小資料,
 * <b>不含</b> Wi-Fi Direct 群組憑證(憑證稍後走 BLE 側通道)。
 *
 * <h3>用途</h3>
 * <ul>
 *   <li><b>session id</b>:{@link #nonce} 同時當 BLE 廣播鍵與場次識別。</li>
 *   <li><b>GO 選舉</b>:{@link #goIntent} 為主、nonce 為輔的決定性比較
 *       ({@link #shouldBeGroupOwner})。雙方各持兩份 token 時會算出一致結果。</li>
 *   <li><b>冷啟動</b>:序列化成 {@code kisslink://pair?...} deep link,
 *       讓對方 App 未開時由 OS NFC dispatch 啟動(情境 1)。</li>
 * </ul>
 *
 * <p>NFC 是單向讀:一次 latch 只有 reader 讀到 tag 的 token。reader 之後透過 BLE
 * 把自己的 token 回送給 tag,兩邊才都湊齊兩份 token 做選舉。
 */
public final class PairingToken {

    public static final int VERSION   = 1;
    public static final int NONCE_LEN = 8;

    private static final int B64_FLAGS =
            Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP;

    public final int    version;
    public final byte[] nonce;      // 8 bytes：場次 id / BLE 廣播鍵 / 選舉 tiebreaker
    public final int    goIntent;   // 0..255：越大越想當 GO
    public final String deviceName; // UI 顯示用（可空）

    public PairingToken(int version, @NonNull byte[] nonce, int goIntent, @Nullable String deviceName) {
        this.version    = version;
        this.nonce      = nonce;
        this.goIntent   = goIntent & 0xFF;
        this.deviceName = deviceName == null ? "" : deviceName;
    }

    /** 本機開一場新配對：隨機 nonce + 隨機 goIntent。 */
    public static PairingToken create(@Nullable String deviceName) {
        SecureRandom rng = new SecureRandom();
        byte[] n = new byte[NONCE_LEN];
        rng.nextBytes(n);
        return new PairingToken(VERSION, n, rng.nextInt(256), deviceName);
    }

    // ── 序列化 ────────────────────────────────────────────────

    public String nonceB64() {
        return Base64.encodeToString(nonce, B64_FLAGS);
    }

    /** 序列化成 deep link(NDEF URI record + OS 冷啟動共用)。 */
    public String toUri() {
        return new Uri.Builder()
                .scheme("kisslink").authority("pair")
                .appendQueryParameter("v", Integer.toString(version))
                .appendQueryParameter("n", nonceB64())
                .appendQueryParameter("g", Integer.toString(goIntent))
                .appendQueryParameter("d", deviceName)
                .build().toString();
    }

    @Nullable
    public static PairingToken fromUri(@Nullable Uri uri) {
        if (uri == null) return null;
        if (!"kisslink".equals(uri.getScheme()) || !"pair".equals(uri.getAuthority())) return null;
        try {
            String nB64 = uri.getQueryParameter("n");
            if (nB64 == null) return null;
            byte[] n = Base64.decode(nB64, B64_FLAGS);
            if (n.length != NONCE_LEN) return null;
            return new PairingToken(
                    parseIntOr(uri.getQueryParameter("v"), VERSION),
                    n,
                    parseIntOr(uri.getQueryParameter("g"), 0),
                    uri.getQueryParameter("d"));
        } catch (Exception e) {
            return null;
        }
    }

    // ── GO 選舉 ───────────────────────────────────────────────

    /**
     * 決定性 GO 選舉:goIntent 大者勝;同分則 nonce 字典序大者勝。
     * 反對稱——雙方以相同的兩份 token 計算,必得相反結果(一方 true、一方 false)。
     */
    public boolean shouldBeGroupOwner(@NonNull PairingToken other) {
        if (this.goIntent != other.goIntent) return this.goIntent > other.goIntent;
        int cmp = compareNonce(this.nonce, other.nonce);
        return cmp > 0; // nonce 相同（天文機率）時雙方皆 false，交由上層重抽
    }

    /** 兩 token 是否同一場次(nonce 相同)。 */
    public boolean sameSession(@Nullable PairingToken other) {
        return other != null && Arrays.equals(this.nonce, other.nonce);
    }

    // ── 工具 ──────────────────────────────────────────────────

    private static int compareNonce(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int d = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (d != 0) return d;
        }
        return a.length - b.length;
    }

    private static int parseIntOr(@Nullable String s, int def) {
        try { return s == null ? def : Integer.parseInt(s); }
        catch (NumberFormatException e) { return def; }
    }

    @Override
    public String toString() {
        return "PairingToken{v=" + version + ", nonce=" + nonceB64()
                + ", goIntent=" + goIntent + ", dev='" + deviceName + "'}";
    }
}
