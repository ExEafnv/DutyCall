package com.eafnv.callforward.ui;

import android.content.Context;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 弹窗构造器入口，目前行为与 Material 默认一致。
 *
 * 保留这个包装是为了将来统一改弹窗样式时只改一处。
 * 注意：不要在这里改窗口背景——Material 会在 onStart 里重新设置，
 * 而且实测这台机器上弹窗窗口的背景会被系统提亮一档，改了反而更乱。
 */
public class DutyDialogBuilder extends MaterialAlertDialogBuilder {

    public DutyDialogBuilder(Context context) {
        super(context);
    }

    public DutyDialogBuilder(Context context, int themeResId) {
        super(context, themeResId);
    }
}
