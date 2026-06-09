package com.kisslink.ui.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.animation.ValueAnimator;

import androidx.annotation.Nullable;

/**
 * C 方案 Beam 的中央視覺——純 Canvas 自繪，吃外部餵入的狀態：
 * <ul>
 *   <li>{@link #READY}：你的點往外擴散的 NFC 漣漪。</li>
 *   <li>{@link #CONNECTING}：光束由你往對方畫上去、對方點浮現。</li>
 *   <li>{@link #CONNECTED}：光束接通、對方點換成頭像/字母。</li>
 *   <li>{@link #TRANSFERRING}：古銅金沿光束依 {@code progress} 填充 + 行進光點；
 *       方向由 {@link #SEND}/{@link #RECEIVE} 決定（收檔時反向）。</li>
 *   <li>{@link #DONE}：光束滿、對方點亮勾。</li>
 * </ul>
 * 「你」固定在下、對方在上。百分比大字由外層 TextView 負責，這裡只畫光束本體。
 */
public class BeamStageView extends View {

    // ── 對外狀態常數 ──────────────────────────────────────────
    public static final int READY = 0, CONNECTING = 1, CONNECTED = 2,
            TRANSFERRING = 3, DONE = 4, ERROR = 5;
    public static final int SEND = 0, RECEIVE = 1;

    // Beam 色票
    private static final int INK    = 0xFF23201B;
    private static final int MUTED  = 0xFF9A948A;
    private static final int TRACK  = 0xFFDCD8CF;
    private static final int ACCENT = 0xFFB07A32;

    private final Paint pTrack  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pAccent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDot    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRing   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGlow   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pAvatar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pMono   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCheck  = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int    phase = READY;
    private int    direction = SEND;
    private float  progress = 0f;       // 0..1，傳輸填充
    private float  linkT = 0f;          // 0..1，光束接通動畫
    private float  anim = 0f;           // 0..1 無限循環，驅動漣漪/光點脈動

    @Nullable private Bitmap selfAvatar, peerAvatar;
    @Nullable private String selfMono, peerMono;
    private int selfMonoColor = INK, peerMonoColor = ACCENT;

    private ValueAnimator loop;          // 無限循環
    private ValueAnimator linkAnim;      // linkT 過場

    private final float density;

    public BeamStageView(Context c) { this(c, null); }
    public BeamStageView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;

        pTrack.setStyle(Paint.Style.STROKE);
        pTrack.setStrokeCap(Paint.Cap.ROUND);
        pTrack.setColor(TRACK);
        pTrack.setStrokeWidth(dp(1.6f));

        pAccent.setStyle(Paint.Style.STROKE);
        pAccent.setStrokeCap(Paint.Cap.ROUND);
        pAccent.setColor(ACCENT);
        pAccent.setStrokeWidth(dp(1.6f));

        pDot.setStyle(Paint.Style.FILL);
        pRing.setStyle(Paint.Style.STROKE);
        pRing.setStrokeWidth(dp(1.4f));

        pGlow.setStyle(Paint.Style.FILL);
        pGlow.setColor(ACCENT);

        pMono.setTextAlign(Paint.Align.CENTER);
        pMono.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));

        pCheck.setStyle(Paint.Style.STROKE);
        pCheck.setStrokeCap(Paint.Cap.ROUND);
        pCheck.setStrokeJoin(Paint.Join.ROUND);
        pCheck.setColor(ACCENT);
        pCheck.setStrokeWidth(dp(1.8f));
    }

    private float dp(float v) { return v * density; }

    // ── 對外 API ──────────────────────────────────────────────

    public void setPhase(int newPhase) {
        if (phase == newPhase) return;
        phase = newPhase;
        boolean linked = (newPhase == CONNECTING || newPhase == CONNECTED
                || newPhase == TRANSFERRING || newPhase == DONE);
        animateLink(linked ? 1f : 0f);
        if (newPhase == READY || newPhase == ERROR) progress = 0f;
        invalidate();
    }

    public void setProgress(float p) {
        progress = Math.max(0f, Math.min(1f, p));
        invalidate();
    }

    public void setDirection(int d) { direction = d; invalidate(); }

    public void setSelfAvatar(@Nullable Bitmap b) { selfAvatar = b; invalidate(); }
    public void setPeerAvatar(@Nullable Bitmap b) { peerAvatar = b; invalidate(); }

    public void setSelfIdentity(@Nullable String name) {
        selfMono = monogram(name); selfMonoColor = INK; invalidate();
    }
    public void setPeerIdentity(@Nullable String name) {
        peerMono = monogram(name); peerMonoColor = ACCENT; invalidate();
    }

    private static String monogram(@Nullable String name) {
        if (name == null) return null;
        String s = name.trim();
        if (s.isEmpty()) return null;
        return s.substring(0, 1).toUpperCase();
    }

    // ── 動畫驅動 ──────────────────────────────────────────────

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        loop = ValueAnimator.ofFloat(0f, 1f);
        loop.setDuration(2600);
        loop.setRepeatCount(ValueAnimator.INFINITE);
        loop.setInterpolator(null);
        loop.addUpdateListener(a -> { anim = (float) a.getAnimatedValue(); invalidate(); });
        loop.start();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (loop != null) loop.cancel();
        if (linkAnim != null) linkAnim.cancel();
    }

    private void animateLink(float target) {
        if (linkAnim != null) linkAnim.cancel();
        linkAnim = ValueAnimator.ofFloat(linkT, target);
        linkAnim.setDuration(target > linkT ? 620 : 380);
        linkAnim.addUpdateListener(a -> { linkT = (float) a.getAnimatedValue(); invalidate(); });
        linkAnim.start();
    }

    // ── 繪製 ──────────────────────────────────────────────────

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f;
        float topY = h * 0.20f, botY = h * 0.80f;
        float span = botY - topY;

        float dotR    = dp(6f);
        float avatarR = dp(24f);

        // 連線後兩端用頭像（較大半徑），未連線用小圓點。
        boolean showPeer = linkT > 0.02f || phase == CONNECTED || phase == TRANSFERRING || phase == DONE;

        // 1) 中性軌道（接通動畫：由你往上畫）
        if (linkT > 0.001f) {
            float tipY = botY - span * easeInOut(linkT);
            c.drawLine(cx, botY, cx, tipY, pTrack);
        }

        // 2) 古銅金進度填充（傳輸 / 完成）
        if (phase == TRANSFERRING || phase == DONE) {
            float fill = (phase == DONE) ? 1f : easeInOut(progress);
            float fromY, toY;
            if (direction == SEND) { fromY = botY; toY = botY - span * fill; }  // 你 → 對方
            else                   { fromY = topY; toY = topY + span * fill; }  // 對方 → 你
            c.drawLine(cx, fromY, cx, toY, pAccent);

            // 行進光點（傳輸中）
            if (phase == TRANSFERRING) {
                float beadPulse = 0.6f + 0.4f * (float) Math.sin(anim * 2 * Math.PI);
                pGlow.setAlpha((int) (180 + 60 * beadPulse));
                c.drawCircle(cx, toY, dp(4.5f), pGlow);
            }
        }

        // 3) READY：NFC 漣漪——以你的點為中心往外擴散
        if (phase == READY) drawRipple(c, cx, botY, span);

        // 4) 端點：對方（上）
        if (showPeer) {
            float aAlpha = Math.min(1f, linkT / 0.4f);
            drawEndpoint(c, cx, topY, avatarR, dotR, peerAvatar, peerMono, peerMonoColor,
                    /*solid*/ false, aAlpha, /*connected*/ phase == CONNECTED || phase == TRANSFERRING || phase == DONE);
            if (phase == DONE) drawCheck(c, cx, topY, avatarR);
        }

        // 5) 端點：你（下）——永遠實心
        boolean youConnected = phase == CONNECTED || phase == TRANSFERRING || phase == DONE;
        drawEndpoint(c, cx, botY, avatarR, dotR, selfAvatar, selfMono, selfMonoColor,
                /*solid*/ true, 1f, youConnected);
    }

    /** 漣漪：2 圈同心圓由小往大擴散並淡出。 */
    private void drawRipple(Canvas c, float cx, float cy, float span) {
        float maxR = span * 0.62f;
        for (int i = 0; i < 2; i++) {
            float t = (anim + i * 0.5f) % 1f;
            float r = dp(10f) + (maxR - dp(10f)) * t;
            int alpha = (int) (110 * (1f - t));
            if (alpha <= 0) continue;
            pRing.setColor(ACCENT);
            pRing.setAlpha(alpha);
            c.drawCircle(cx, cy, r, pRing);
        }
    }

    /**
     * 端點：有頭像 → 圓形裁切；否則字母 monogram；都沒有 → 圓點/圓環。
     * @param solid    你的點未連線時為實心墨點
     * @param connected 已連線（用大頭像半徑），否則小圓點
     */
    private void drawEndpoint(Canvas c, float cx, float cy, float avatarR, float dotR,
                              @Nullable Bitmap avatar, @Nullable String mono, int monoColor,
                              boolean solid, float alpha, boolean connected) {
        int a = (int) (255 * alpha);
        if (connected && (avatar != null || mono != null)) {
            if (avatar != null) {
                BitmapShader sh = new BitmapShader(
                        circularBitmap(avatar), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                // 將 shader 對位到端點圓
                android.graphics.Matrix m = new android.graphics.Matrix();
                float scale = (avatarR * 2f) / circularSize;
                m.setScale(scale, scale);
                m.postTranslate(cx - avatarR, cy - avatarR);
                sh.setLocalMatrix(m);
                pAvatar.setShader(sh);
                pAvatar.setAlpha(a);
                c.drawCircle(cx, cy, avatarR, pAvatar);
                pAvatar.setShader(null);
                pRing.setColor(TRACK); pRing.setAlpha(a);
                c.drawCircle(cx, cy, avatarR, pRing);
            } else {
                pDot.setColor(0xFFF4F2EC); pDot.setAlpha(a);
                c.drawCircle(cx, cy, avatarR, pDot);
                pRing.setColor(monoColor); pRing.setAlpha(a);
                c.drawCircle(cx, cy, avatarR, pRing);
                pMono.setColor(monoColor); pMono.setAlpha(a);
                pMono.setTextSize(avatarR * 1.05f);
                float ty = cy - (pMono.descent() + pMono.ascent()) / 2f;
                c.drawText(mono, cx, ty, pMono);
            }
            return;
        }
        // 未連線：小圓點（你=實心墨點；對方=圓環）
        if (solid) {
            pDot.setColor(INK); pDot.setAlpha(a);
            c.drawCircle(cx, cy, dotR, pDot);
        } else {
            pRing.setColor(phase == DONE ? ACCENT : INK); pRing.setAlpha(a);
            c.drawCircle(cx, cy, dotR, pRing);
        }
    }

    private void drawCheck(Canvas c, float cx, float cy, float r) {
        float s = r * 0.5f;
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(cx - s * 0.7f, cy);
        p.lineTo(cx - s * 0.1f, cy + s * 0.6f);
        p.lineTo(cx + s * 0.8f, cy - s * 0.5f);
        c.drawPath(p, pCheck);
    }

    // ── 圓形頭像快取 ──────────────────────────────────────────
    private Bitmap circularCacheSrc;
    private Bitmap circularCache;
    private float circularSize = 1f;

    /** 把任意 bitmap 置中裁成正方形（再由 shader 畫成圓）。 */
    private Bitmap circularBitmap(Bitmap src) {
        if (src == circularCacheSrc && circularCache != null) return circularCache;
        int size = Math.min(src.getWidth(), src.getHeight());
        int left = (src.getWidth() - size) / 2;
        int top = (src.getHeight() - size) / 2;
        circularCache = Bitmap.createBitmap(src, left, top, size, size);
        circularCacheSrc = src;
        circularSize = size;
        return circularCache;
    }

    private static float easeInOut(float x) {
        return x < 0.5f ? 4f * x * x * x : 1f - (float) Math.pow(-2f * x + 2f, 3) / 2f;
    }
}
