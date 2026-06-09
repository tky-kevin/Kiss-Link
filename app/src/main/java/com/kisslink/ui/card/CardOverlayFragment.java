package com.kisslink.ui.card;

import android.animation.ObjectAnimator;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.kisslink.R;
import com.kisslink.data.repository.UserProfileRepository;
import com.kisslink.model.BusinessCard;
import com.kisslink.model.UserProfile;
import com.kisslink.nfc.KissLinkHCEService;

public class CardOverlayFragment extends DialogFragment {

    private enum ShareState { IDLE, WAITING }
    private ShareState state = ShareState.IDLE;

    private View cardAnimTarget;
    private MaterialButton btnShare;
    private ObjectAnimator swayAnimator;
    private float swipeStartY = 0f;

    // ══════════════════════════════════════════════════════════
    //  Dialog 建立
    // ══════════════════════════════════════════════════════════

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_card_overlay, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View rootView, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(rootView, savedInstanceState);

        cardAnimTarget = rootView.findViewById(R.id.businessCardView);
        btnShare = rootView.findViewById(R.id.btnShare);

        btnShare.setOnClickListener(v -> onShareClicked());

        populateCard(rootView);

        // 向上滑動關閉
        cardAnimTarget.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    swipeStartY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_UP:
                    float deltaY = swipeStartY - event.getRawY(); // 正值 = 往上滑
                    if (deltaY > 120) { // 上滑超過 120dp 就關閉
                        dismissWithSwipeUp();
                        return true;
                    }
                    break;
            }
            return false;
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.setCanceledOnTouchOutside(true);

            // 背景虛化（API 31+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
                lp.blurBehindRadius = 25;
                dialog.getWindow().setAttributes(lp);
            }
        }
        // 播放進場動畫：card 從上方飛入中央
        playEnterAnimation();
    }

    // ══════════════════════════════════════════════════════════
    //  填充名片資料
    // ══════════════════════════════════════════════════════════

    private void populateCard(View rootView) {
        UserProfileRepository repo = UserProfileRepository.getInstance(requireContext());
        BusinessCard card = repo.getBusinessCard();
        UserProfile profile = repo.getUserProfile();

        // 頭像
        ShapeableImageView ivAvatar = rootView.findViewById(R.id.ivCardAvatar);
        if (ivAvatar != null) {
            String avatarUri = card.getAvatarUri();
            if (avatarUri == null || avatarUri.isEmpty()) {
                avatarUri = profile.getAvatarUri();
            }
            if (avatarUri != null && !avatarUri.isEmpty()) {
                ivAvatar.setImageURI(Uri.parse(avatarUri));
            } else {
                ivAvatar.setImageResource(R.drawable.avatar_placeholder);
            }
        }

        // 姓名
        setText(rootView, R.id.tvCardName, card.getName(), true);

        // 學校 · 科系
        TextView tvSchoolMajor = rootView.findViewById(R.id.tvCardSchoolMajor);
        if (tvSchoolMajor != null) {
            String school = card.getSchool();
            String major  = card.getMajor();
            if (notEmpty(school) || notEmpty(major)) {
                StringBuilder sb = new StringBuilder();
                if (notEmpty(school)) sb.append(school);
                if (notEmpty(major)) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(major);
                }
                tvSchoolMajor.setText(sb.toString());
                tvSchoolMajor.setVisibility(View.VISIBLE);
            } else {
                tvSchoolMajor.setVisibility(View.GONE);
            }
        }

        // Bio
        setTextOrGone(rootView, R.id.tvCardBio, card.getBio());

        // IG
        TextView tvIg = rootView.findViewById(R.id.tvCardIg);
        if (tvIg != null) {
            String ig = card.getIg();
            if (notEmpty(ig)) {
                tvIg.setText("📸 " + (ig.startsWith("@") ? ig : "@" + ig));
                tvIg.setVisibility(View.VISIBLE);
            } else {
                tvIg.setVisibility(View.GONE);
            }
        }

        // LINE
        setTextOrGone(rootView, R.id.tvCardLine, notEmpty(card.getLineId()) ? "💬 " + card.getLineId() : null);
    }

    private void setText(View root, int id, String text, boolean alwaysShow) {
        TextView tv = root.findViewById(id);
        if (tv == null) return;
        tv.setText(text != null ? text : "");
    }

    private void setTextOrGone(View root, int id, String text) {
        TextView tv = root.findViewById(id);
        if (tv == null) return;
        if (notEmpty(text)) {
            tv.setText(text);
            tv.setVisibility(View.VISIBLE);
        } else {
            tv.setVisibility(View.GONE);
        }
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    // ══════════════════════════════════════════════════════════
    //  動畫
    // ══════════════════════════════════════════════════════════

    private void playEnterAnimation() {
        if (cardAnimTarget == null) return;
        cardAnimTarget.setTranslationY(-1200f);
        cardAnimTarget.setAlpha(0f);
        cardAnimTarget.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(500)
                .setInterpolator(new DecelerateInterpolator(2.5f))
                .start();
    }

    private void playFlyAwayAndDismiss() {
        if (cardAnimTarget == null) { dismiss(); return; }
        cardAnimTarget.animate()
                .translationY(-2000f)
                .alpha(0f)
                .setDuration(500)
                .setInterpolator(new AccelerateInterpolator(2.0f))
                .withEndAction(this::dismiss)
                .start();
    }

    private void startSwayAnimation() {
        stopSwayAnimation(); // 防止重複
        swayAnimator = ObjectAnimator.ofFloat(cardAnimTarget, "rotation", -2.5f, 2.5f);
        swayAnimator.setDuration(1400);
        swayAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        swayAnimator.setRepeatMode(ObjectAnimator.REVERSE);
        swayAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        swayAnimator.start();
    }

    private void stopSwayAnimation() {
        if (swayAnimator != null) {
            swayAnimator.cancel();
            if (cardAnimTarget != null) {
                cardAnimTarget.setRotation(0f); // 重設角度
            }
            swayAnimator = null;
        }
    }

    private void dismissWithSwipeUp() {
        stopSwayAnimation();
        KissLinkHCEService.clearCredential();
        KissLinkHCEService.clearOnCardDeliveredCallback();
        // 卡片往上飛出
        if (cardAnimTarget == null) { dismiss(); return; }
        cardAnimTarget.animate()
                .translationY(-2000f)
                .alpha(0f)
                .setDuration(350)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(2f))
                .withEndAction(this::dismiss)
                .start();
    }

    // ══════════════════════════════════════════════════════════
    //  分享流程
    // ══════════════════════════════════════════════════════════

    private void onShareClicked() {
        if (state != ShareState.IDLE) return;
        state = ShareState.WAITING;

        btnShare.setEnabled(false);
        btnShare.setText("靠近對方手機中...");

        // 輕微左右晃動（一直持續到 NFC 完成）
        startSwayAnimation();

        // 設定 HCE
        BusinessCard card = UserProfileRepository.getInstance(requireContext()).getBusinessCard();
        KissLinkHCEService.setBusinessCard(card);

        // NFC 送達回調
        KissLinkHCEService.setOnCardDeliveredCallback(() -> {
            if (isAdded()) requireActivity().runOnUiThread(this::onCardDelivered);
        });
    }

    private void onCardDelivered() {
        stopSwayAnimation();
        KissLinkHCEService.clearCredential();
        KissLinkHCEService.clearOnCardDeliveredCallback();
        playFlyAwayAndDismiss();
    }

    // ══════════════════════════════════════════════════════════
    //  清理
    // ══════════════════════════════════════════════════════════

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        stopSwayAnimation();
        KissLinkHCEService.clearCredential();
        KissLinkHCEService.clearOnCardDeliveredCallback();
    }
}
