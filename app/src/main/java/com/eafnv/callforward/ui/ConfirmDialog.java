package com.eafnv.callforward.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.eafnv.callforward.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 统一的二次确认弹窗：居中布局、项目自己的实心按钮。
 */
public final class ConfirmDialog {

    /** 确认后的回调。 */
    public interface OnConfirm {
        void run();
    }

    private ConfirmDialog() {
    }

    /**
     * 弹出一个确认框。
     *
     * @param confirmText 确认按钮文案，例如「删除」「移出」
     * @param destructive 是否为破坏性操作，是则按钮用错误色
     */
    public static void show(Context context, String title, String message,
                            String confirmText, boolean destructive, final OnConfirm action) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_confirm, null);
        TextView tvMessage = view.findViewById(R.id.tv_message);
        MaterialButton btnCancel = view.findViewById(R.id.btn_cancel);
        MaterialButton btnConfirm = view.findViewById(R.id.btn_confirm);

        tvMessage.setText(message);
        btnConfirm.setText(confirmText);

        if (destructive) {
            btnConfirm.setBackgroundTintList(ColorStateList.valueOf(
                    context.getColor(R.color.destructive_bg)));
            btnConfirm.setTextColor(context.getColor(android.R.color.holo_red_dark));
        }

        final AlertDialog dialog = new com.eafnv.callforward.ui.DutyDialogBuilder(context)
                .setTitle(title)
                .setView(view)
                .setPositiveButton(null, null)
                .setNegativeButton(null, null)
                .create();
        dialog.show();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            dialog.dismiss();
            if (action != null) action.run();
        });
    }
}
