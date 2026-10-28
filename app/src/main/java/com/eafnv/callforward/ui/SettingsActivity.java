package com.eafnv.callforward.ui;

import android.Manifest;
import android.app.AlarmManager;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.eafnv.callforward.R;
import com.eafnv.callforward.mmi.AutoSwitchScheduler;
import com.eafnv.callforward.mmi.MmiExecutor;
import com.eafnv.callforward.service.DutyAccessibilityService;
import com.eafnv.callforward.util.AppPrefs;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 设置页，集中管理转移方式、自动执行和排班进度微调。
 *
 * 卡片只保留一行副标题，完整说明放在标题旁的帮助弹窗里。
 */
public class SettingsActivity extends AppCompatActivity {

    private static final int REQ_NOTIFICATION = 2001;

    private RadioGroup rgMode;
    private MaterialSwitch switchAuto;
    /** 成功提示是否弹悬浮窗卡片。 */
    private MaterialSwitch switchPopup;
    private TextView tvTimeValue;
    private TextView tvOffsetSummary;
    private TextView tvModeNote;
    private TextView tvA11yAction;
    private LinearLayout layoutIssues;

    private int tempOffsetM;
    private int tempOffsetW;
    private int tempOffsetD;

    /** 上一次应用下去的卡片内边距，用来从内容高度里扣除，避免自我引用。 */
    private int appliedPadPx = -1;
    /** 上一次应用下去的卡片间距，同样用于从内容高度里扣除。 */
    private int appliedGapPx = -1;

    /** 初始化各卡片控件、同步已保存的配置并挂上监听。 */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        rgMode = findViewById(R.id.rg_mode);
        switchAuto = findViewById(R.id.switch_auto);
        tvTimeValue = findViewById(R.id.tv_time_value);
        tvOffsetSummary = findViewById(R.id.tv_offset_summary);
        tvModeNote = findViewById(R.id.tv_mode_note);
        tvA11yAction = findViewById(R.id.tv_a11y_action);
        layoutIssues = findViewById(R.id.layout_issues);

        // 先同步控件状态，再挂监听，避免初始化时触发切换逻辑。
        rgMode.check(AppPrefs.isDialMode(this) ? R.id.rb_dial : R.id.rb_silent);
        switchAuto.setChecked(AppPrefs.autoEnabled(this));
        updateTimeText();
        updateOffsetSummary();
        updateModeNote();

        rgMode.setOnCheckedChangeListener((group, checkedId) -> onModeChanged(checkedId));
        switchAuto.setOnCheckedChangeListener((button, checked) -> onAutoToggled(checked));
        switchPopup = findViewById(R.id.switch_popup);
        switchPopup.setChecked(AppPrefs.popupEnabled(this));
        switchPopup.setOnCheckedChangeListener((button, checked) ->
                AppPrefs.setPopupEnabled(this, checked));

        findViewById(R.id.row_time).setOnClickListener(v -> showTimePicker());
        findViewById(R.id.row_fine_tune).setOnClickListener(v -> showFineTuneDialog());
        findViewById(R.id.row_a11y).setOnClickListener(v -> onAccessibilityRowClick());
        findViewById(R.id.row_formula_manager).setOnClickListener(v ->
                startActivity(new Intent(this, FormulaActivity.class)));
        findViewById(R.id.iv_mode_help).setOnClickListener(v -> showModeHelp());
        findViewById(R.id.iv_auto_help).setOnClickListener(v -> showAutoHelp());

        setupAdaptiveCompactness();
    }

    /** 回到前台时同步自动转移、无障碍状态并重新计算卡片间距。 */
    @Override
    protected void onResume() {
        super.onResume();
        // 后台可能被通知栏的“停止自动转移”关闭，这里以存储为准。
        boolean enabled = AppPrefs.autoEnabled(this);
        if (switchAuto.isChecked() != enabled) {
            switchAuto.setChecked(enabled);
        }
        if (enabled && hasOverlay()) {
            // 用户可能刚在系统设置里授予悬浮窗权限，这里让常驻服务补挂图标。
            MmiExecutor.startAgent(this);
        }
        updateModeNote();
        refreshAutoStatus();
        refreshAccessibilityStatus();
        applyAdaptiveCompactness();
    }

    /**
     * 挂上紧凑度自适应：首次布局后算一次，之后内容高度变化时自动重算。
     *
     * 用 OnGlobalLayoutListener 而不是一次性 post：卡片内容会变（横幅出现/消失），
     * 但计算本身是幂等的（结果不变就不 setPadding），不会形成来回抖的循环。
     */
    private void setupAdaptiveCompactness() {
        final View scroll = findViewById(R.id.settings_scroll);
        final ViewGroup content = findViewById(R.id.settings_content);
        if (scroll == null || content == null) return;
        content.getViewTreeObserver().addOnGlobalLayoutListener(this::applyAdaptiveCompactness);
        scroll.post(this::applyAdaptiveCompactness);
    }

    /**
     * 卡片紧凑度自适应：把剩余高度分配给「卡片内边距」和「卡片间距」两个可压缩量。
     *
     * 优先撑/收卡片内边距（10–38dp）；内边距收到下限还放不下时，
     * 再把卡片间距从 8dp 往下压（2–8dp），尽量做到一屏放下不滚动。
     *
     * 计算只用「视口 - 内容自然高度」，自然高度里**不含内边距和间距**：
     * content.getHeight() 本身已经把两者算进去了，直接拿它算会出现
     * "改一次 → 高度变 → 再算又不一样" 的来回抖。
     *
     * 结果与上一次相同则什么都不做，因此可以放心地重复调用。
     */
    private void applyAdaptiveCompactness() {
        final View scroll = findViewById(R.id.settings_scroll);
        final ViewGroup content = findViewById(R.id.settings_content);
        if (scroll == null || content == null) return;
        int viewport = scroll.getHeight();
        if (viewport <= 0 || content.getHeight() <= 0) return;

        int cards = 0;
        for (int i = 0; i < content.getChildCount(); i++) {
            if (content.getChildAt(i) instanceof MaterialCardView) cards++;
        }
        if (cards == 0) return;

        // 首次计算时还没记录过，取布局里的实际值当基线
        int currentPad = appliedPadPx >= 0 ? appliedPadPx : currentCardPadding(content);
        int currentGap = appliedGapPx >= 0 ? appliedGapPx : currentCardGap(content);
        int gaps = Math.max(0, cards - 1);
        int natural = content.getHeight() - currentPad * cards * 2 - currentGap * gaps;

        int need = viewport - scroll.getPaddingBottom() - natural;

        // 两段式压缩：先按常规间距分内边距；不够就把间距压到下限、内边距下限也放宽。
        int pad;
        int gap = dp(8);
        int normalMin = dp(10) * cards * 2 + gap * gaps;
        if (need >= normalMin) {
            pad = (need - gap * gaps) / (cards * 2);
            pad = Math.max(dp(10), Math.min(dp(38), pad));
        } else {
            gap = gaps > 0 ? dp(2) : 0;
            pad = (need - gap * gaps) / (cards * 2);
            pad = Math.max(dp(4), Math.min(dp(10), pad));
        }

        appliedPadPx = pad;
        appliedGapPx = gap;
        for (int i = 0; i < content.getChildCount(); i++) {
            View card = content.getChildAt(i);
            if (!(card instanceof MaterialCardView)) continue;
            if (((ViewGroup) card).getChildCount() == 0) continue;

            ViewGroup.LayoutParams lp = card.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mp = (ViewGroup.MarginLayoutParams) lp;
                if (mp.topMargin != gap && i > 0) {
                    mp.topMargin = gap;
                    card.setLayoutParams(mp);
                }
            }
            View inner = ((ViewGroup) card).getChildAt(0);
            int l = inner.getPaddingLeft();
            int r = inner.getPaddingRight();
            if (inner.getPaddingTop() != pad || inner.getPaddingBottom() != pad) {
                inner.setPadding(l, pad, r, pad);
            }
        }
    }

    /** 取任意一张卡片当前的内边距，作为自适应计算的基线。 */
    private int currentCardPadding(ViewGroup content) {
        for (int i = 0; i < content.getChildCount(); i++) {
            View card = content.getChildAt(i);
            if (!(card instanceof MaterialCardView)) continue;
            if (((ViewGroup) card).getChildCount() == 0) continue;
            return ((ViewGroup) card).getChildAt(0).getPaddingTop();
        }
        return 0;
    }

    /** 取第一张带外边距的卡片的 topMargin，作为卡片间距的基线。 */
    private int currentCardGap(ViewGroup content) {
        for (int i = 1; i < content.getChildCount(); i++) {
            View card = content.getChildAt(i);
            if (!(card instanceof MaterialCardView)) continue;
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                int top = ((ViewGroup.MarginLayoutParams) lp).topMargin;
                if (top > 0) return top;
            }
        }
        return 0;
    }

    // ===== 转移方式 =====

    /** 切换转移方式后刷新说明和自动任务。 */
    private void onModeChanged(int checkedId) {
        String mode = checkedId == R.id.rb_dial ? AppPrefs.MODE_DIAL : AppPrefs.MODE_SILENT;
        if (mode.equals(AppPrefs.mode(this))) return;
        AppPrefs.setMode(this, mode);
        updateModeNote();
        refreshAutoStatus();
        if (AppPrefs.autoEnabled(this)) {
            MmiExecutor.refresh(this);
        }
    }

    /** 根据当前模式刷新卡片副标题。 */
    private void updateModeNote() {
        tvModeNote.setText(AppPrefs.isDialMode(this)
                ? "兼容模式：会弹系统提示，不支持后台执行"
                : "静默注册：无弹窗，支持后台执行");
    }

    // ===== 自动执行 =====

    /**
     * 开启或关闭自动转移。
     *
     * 开启时补登记今日状态，启动常驻服务与定时任务；关闭时停止服务并取消定时。
     */
    private void onAutoToggled(boolean checked) {
        AppPrefs.setAutoEnabled(this, checked);
        if (checked) {
            // 如果今天的触发时间已经过去，先标记为今日已处理，
            // 避免刚开启就被“解锁补偿”立刻切一次。
            markTodayHandledIfPast();
            if (!hasCallPermission()) {
                Toast.makeText(this, "请先授予拨号权限，否则自动转移无法执行", Toast.LENGTH_LONG).show();
            }
            MmiExecutor.startAgent(this);
            AutoSwitchScheduler.schedule(this);
            ensureNotificationPermission();
        } else {
            MmiExecutor.stopAgent(this);
            AutoSwitchScheduler.cancel(this);
        }
        refreshAutoStatus();
    }

    /** 若今日触发时间已过，先记为今日已处理，避免开启时立刻补切。 */
    private void markTodayHandledIfPast() {
        java.time.LocalTime now = java.time.LocalTime.now();
        java.time.LocalTime target = java.time.LocalTime.of(AppPrefs.autoHour(this), AppPrefs.autoMinute(this));
        if (!now.isBefore(target)) {
            AppPrefs.get(this).edit()
                    .putLong(AppPrefs.LAST_AUTO_DAY, java.time.LocalDate.now().toEpochDay())
                    .apply();
        }
    }

    /** 选择自动转移时间，修改后按需重排定时任务。 */
    private void showTimePicker() {
        int hour = AppPrefs.autoHour(this);
        int minute = AppPrefs.autoMinute(this);
        new TimePickerDialog(this, (view, h, m) -> {
            AppPrefs.setAutoTime(this, h, m);
            updateTimeText();
            if (AppPrefs.autoEnabled(this)) {
                AutoSwitchScheduler.schedule(this);
                MmiExecutor.refresh(this);
            }
        }, hour, minute, true).show();
    }

    private void updateTimeText() {
        tvTimeValue.setText(String.format(Locale.getDefault(), "%02d:%02d",
                AppPrefs.autoHour(this), AppPrefs.autoMinute(this)));
    }

    /** 汇总悬浮窗、通知、闹钟与电池优化等权限问题，并展示修复入口。 */
    private void refreshAutoStatus() {
        List<Issue> issues = new ArrayList<>();

        if (!hasOverlay()) {
            issues.add(new Issue("悬浮窗未开启，状态图标不显示", this::openOverlaySettings));
        }
        if (!hasNotificationPermission()) {
            issues.add(new Issue("通知未开启，无法显示常驻状态", this::ensureNotificationPermission));
        }
        if (!canScheduleExactAlarms()) {
            issues.add(new Issue("没有闹钟权限，定时可能被延后", this::openExactAlarmSettings));
        }
        if (!isIgnoringBatteryOptimizations()) {
            issues.add(new Issue("电池优化未关闭，熄屏后可能被冻结", this::requestIgnoreBatteryOptimizations));
        }

        layoutIssues.removeAllViews();
        if (issues.isEmpty()) {
            layoutIssues.setVisibility(View.GONE);
            applyAdaptiveCompactness();
            return;
        }
        for (Issue issue : issues) {
            layoutIssues.addView(buildIssueRow(issue));
        }
        layoutIssues.setVisibility(View.VISIBLE);
        // 提示行会改变卡片高度，重新分配间距。
        applyAdaptiveCompactness();
    }

    /** 构造一条权限提示行：图标、说明文案与修复入口。 */
    private View buildIssueRow(final Issue issue) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_issue);
        int padH = dp(12);
        int padV = dp(10);
        row.setPadding(padH, padV, padH, padV);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(8);
        row.setLayoutParams(rowParams);

        int accent = MaterialColors.getColor(this,
                androidx.appcompat.R.attr.colorPrimary,
                ContextCompat.getColor(this, android.R.color.black));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_info);
        icon.setColorFilter(accent);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(16), dp(16));
        iconParams.setMarginEnd(dp(8));
        row.addView(icon, iconParams);

        TextView text = new TextView(this);
        text.setText(issue.text);
        text.setTextSize(12f);
        text.setTextColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                ContextCompat.getColor(this, android.R.color.darker_gray)));
        row.addView(text, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView action = new TextView(this);
        action.setText("去修复");
        action.setTextSize(12f);
        action.setTypeface(Typeface.DEFAULT_BOLD);
        action.setTextColor(accent);
        action.setPadding(dp(8), dp(2), 0, dp(2));
        row.addView(action);

        row.setOnClickListener(v -> issue.action.run());
        return row;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** 权限提示行所需的数据：文案与对应的修复操作。 */
    private static final class Issue {
        final String text;
        final Runnable action;

        Issue(String text, Runnable action) {
            this.text = text;
            this.action = action;
        }
    }

    // ===== 解锁自动执行（无障碍） =====

    /** 刷新无障碍服务的开关状态文案。 */
    private void refreshAccessibilityStatus() {
        boolean enabled = DutyAccessibilityService.isEnabled(this);
        tvA11yAction.setText(enabled ? "已开启" : "去开启");
    }

    /** 已开启时给出提示，否则跳转系统无障碍设置。 */
    private void onAccessibilityRowClick() {
        if (DutyAccessibilityService.isEnabled(this)) {
            Toast.makeText(this, "解锁自动执行已开启", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "请在列表中找到 DutyCall 并开启", Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "请到「系统设置 → 无障碍」中开启本应用", Toast.LENGTH_LONG).show();
        }
    }

    // ===== 权限 =====

    private boolean hasCallPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasOverlay() {
        return Settings.canDrawOverlays(this);
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Android 13 及以上按需申请通知权限。 */
    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (hasNotificationPermission()) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
    }

    /** Android 12 及以上检查精确闹钟权限，低版本默认允许。 */
    private boolean canScheduleExactAlarms() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    private void openOverlaySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "请在系统设置中授予悬浮窗权限", Toast.LENGTH_LONG).show();
        }
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "请在系统设置中允许「闹钟和提醒」", Toast.LENGTH_LONG).show();
        }
    }

    private boolean isIgnoringBatteryOptimizations() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    /** 请求忽略电池优化，失败时退回电池优化列表。 */
    private void requestIgnoreBatteryOptimizations() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (ActivityNotFoundException ignored) {
                Toast.makeText(this, "请在系统设置中允许本应用后台运行", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 通知权限申请返回后刷新权限提示。 */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATION) {
            refreshAutoStatus();
        }
    }

    // ===== 详细说明弹窗 =====

    /** 展示转移方式的完整说明。 */
    private void showModeHelp() {
        String text = "静默注册\n"
                + "通过系统的 USSD 通道直接与运营商交互，不打开拨号盘，"
                + "前台和后台都不会有任何弹窗。后台自动执行只能用它。\n\n"
                + "兼容模式\n"
                + "走系统拨号通道。执行时系统会弹出一张「来电转接 注册成功」的提示，"
                + "而且只有在前台手动点击时可用——系统不允许非默认拨号器在后台拨出转移指令。";
        showHelpDialog("转移方式说明", text);
    }

    /** 展示自动执行与解锁补偿的完整说明。 */
    private void showAutoHelp() {
        String text = "每天到达设定时间，自动把呼叫转移切换到当日值班人，全程静默，"
                + "同一天只会执行一次。\n\n"
                + "解锁时立即执行\n"
                + "部分国产系统（魅族、华为等）熄屏后会冻结后台进程，定时会被丢弃。"
                + "开启无障碍服务后，系统会持续绑定本应用，解锁手机的瞬间立即检查并补执行，"
                + "是这类机型上最可靠的方式。该服务不读取屏幕内容，需要手动在"
                + "「设置 → 无障碍」中开启。\n\n"
                + "如果以上都不可用，打开 App 时也会自动补执行当天漏掉的任务。\n\n"
                + "后台切换成功后，屏幕上方会弹出一张「已转移到 X」的提示卡片；通知栏里也留一条记录，点开即可回到 App。点一下卡片即可回到 App，几秒后自动淡出。"
                + "通知栏常驻显示今日值班人与当前转移状态，点击屏幕上的悬浮图标可回到 App。";
        showHelpDialog("自动执行说明", text);
    }

    private void showHelpDialog(String title, String message) {
        new com.eafnv.callforward.ui.DutyDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show();
    }

    // ===== 排班进度微调 =====

    private void updateOffsetSummary() {
        tvOffsetSummary.setText(String.format(Locale.getDefault(), "月 %s · 周 %s · 日 %s",
                formatOffset(AppPrefs.offsetM(this)),
                formatOffset(AppPrefs.offsetW(this)),
                formatOffset(AppPrefs.offsetD(this))));
    }

    private String formatOffset(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    /** 打开排班进度微调弹窗，确认后保存三项偏移。 */
    private void showFineTuneDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_fine_tune, null);
        TextView tvM = view.findViewById(R.id.tv_m_value);
        TextView tvW = view.findViewById(R.id.tv_w_value);
        TextView tvD = view.findViewById(R.id.tv_d_value);

        tempOffsetM = AppPrefs.offsetM(this);
        tempOffsetW = AppPrefs.offsetW(this);
        tempOffsetD = AppPrefs.offsetD(this);

        tvM.setText(String.valueOf(tempOffsetM));
        tvW.setText(String.valueOf(tempOffsetW));
        tvD.setText(String.valueOf(tempOffsetD));

        view.findViewById(R.id.btn_m_minus).setOnClickListener(v -> {
            tempOffsetM--;
            tvM.setText(String.valueOf(tempOffsetM));
        });
        view.findViewById(R.id.btn_m_plus).setOnClickListener(v -> {
            tempOffsetM++;
            tvM.setText(String.valueOf(tempOffsetM));
        });
        view.findViewById(R.id.btn_w_minus).setOnClickListener(v -> {
            tempOffsetW--;
            tvW.setText(String.valueOf(tempOffsetW));
        });
        view.findViewById(R.id.btn_w_plus).setOnClickListener(v -> {
            tempOffsetW++;
            tvW.setText(String.valueOf(tempOffsetW));
        });
        view.findViewById(R.id.btn_d_minus).setOnClickListener(v -> {
            tempOffsetD--;
            tvD.setText(String.valueOf(tempOffsetD));
        });
        view.findViewById(R.id.btn_d_plus).setOnClickListener(v -> {
            tempOffsetD++;
            tvD.setText(String.valueOf(tempOffsetD));
        });

        MaterialButton btnCancel = view.findViewById(R.id.btn_cancel);
        MaterialButton btnConfirm = view.findViewById(R.id.btn_confirm);

        AlertDialog dialog = new com.eafnv.callforward.ui.DutyDialogBuilder(this)
                .setCustomTitle(buildCenteredTitle("排班进度微调"))
                .setView(view)
                .setPositiveButton(null, null)
                .setNegativeButton(null, null)
                .create();
        dialog.show();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            AppPrefs.setOffsets(this, tempOffsetM, tempOffsetW, tempOffsetD);
            updateOffsetSummary();
            dialog.dismiss();
            MmiExecutor.refresh(this);
        });
    }

    /** Material 弹窗标题默认左对齐，这里用自定义标题实现居中。 */
    private View buildCenteredTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurface,
                ContextCompat.getColor(this, android.R.color.black)));
        int padding = dp(24);
        title.setPadding(padding, padding, padding, 0);
        return title;
    }
}
