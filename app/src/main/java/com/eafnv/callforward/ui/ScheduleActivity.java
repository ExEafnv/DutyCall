package com.eafnv.callforward.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eafnv.callforward.R;
import com.eafnv.callforward.data.AppDatabase;
import com.eafnv.callforward.data.DayOverride;
import com.eafnv.callforward.data.Person;
import com.eafnv.callforward.data.ScheduleEngine;
import com.eafnv.callforward.data.ScheduleMode;
import com.eafnv.callforward.data.ScheduleVersion;
import com.google.android.material.appbar.MaterialToolbar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 排班页，展示当前规则、未来两周的排班预览，并支持临时换人与公式查看。
 *
 * 预览计算在单线程池中完成，避免按天查询数据库时阻塞主线程。
 */
public class ScheduleActivity extends AppCompatActivity {

    private static final String PREFS = "duty_prefs";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private AppDatabase db;
    private SharedPreferences prefs;
    private SchedulePreviewAdapter adapter;

    // 排班偏移量，每次加载数据时从偏好设置重新读取。
    private int offsetM = 0, offsetW = 0, offsetD = 0;

    private TextView tvRuleSummary, tvRuleDetail;
    private TextView tvPreviewTitle, tvPreviewHint, tvPreviewEmpty;
    private TextView tvCopyHint;
    private RecyclerView recyclerPreview;
    private View cardPreview;
    private View btnDeleteRule;

    private List<Person> people = new ArrayList<>();
    private List<ScheduleVersion> allVersions = new ArrayList<>();
    private ScheduleVersion todayVersion;
    private ScheduleVersion upcomingVersion;

    // 公式弹窗的临时状态。
    private AlertDialog formulaDialog;
    private android.widget.EditText formulaEdit;
    private ScheduleVersion formulaVersion;
    private androidx.activity.result.ActivityResultLauncher<Intent> formulaPicker;

    /** 初始化状态栏、列表、公式选择器与各点击入口。 */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_schedule);

        // ===== 状态栏 =====
        boolean isDarkMode = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        androidx.core.view.WindowInsetsControllerCompat controller =
                androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(!isDarkMode);
            controller.setAppearanceLightNavigationBars(!isDarkMode);
        }

        db = AppDatabase.get(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvRuleSummary = findViewById(R.id.tv_rule_summary);
        tvRuleDetail = findViewById(R.id.tv_rule_detail);
        tvCopyHint = findViewById(R.id.tv_copy_hint);
        tvPreviewTitle = findViewById(R.id.tv_preview_title);
        tvPreviewHint = findViewById(R.id.tv_preview_hint);
        tvPreviewEmpty = findViewById(R.id.tv_preview_empty);
        recyclerPreview = findViewById(R.id.recycler_preview);
        cardPreview = findViewById(R.id.card_preview);
        btnDeleteRule = findViewById(R.id.btn_delete_rule);

        adapter = new SchedulePreviewAdapter();
        recyclerPreview.setLayoutManager(new LinearLayoutManager(this));
        recyclerPreview.setAdapter(adapter);
        adapter.setOnDayClick(this::showOverrideDialog);

        findViewById(R.id.btn_edit_rule).setOnClickListener(v ->
                startActivity(new Intent(this, RuleWizardActivity.class)));

        btnDeleteRule.setOnClickListener(v -> confirmDeleteAll());

        // 备注过长时用跑马灯滚动。
        tvRuleSummary.setSingleLine(true);
        tvRuleSummary.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        tvRuleSummary.setMarqueeRepeatLimit(-1);
        tvRuleSummary.setSelected(true);

        formulaPicker = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    if (formulaVersion == null) return;
                    long id = result.getData().getLongExtra(FormulaActivity.EXTRA_ID, 0);
                    String name = result.getData().getStringExtra(FormulaActivity.EXTRA_NAME);
                    String expr = result.getData().getStringExtra(FormulaActivity.EXTRA_EXPR);
                    if (id <= 0 || expr == null) return;

                    formulaVersion.formulaId = id;
                    formulaVersion.formulaRemark = name;
                    formulaVersion.extraParams = expr;
                    if (formulaEdit != null) formulaEdit.setText(expr);
                    if (formulaDialog != null) formulaDialog.dismiss();
                    persistVersion(formulaVersion, "已更换公式");
                });
    }

    /** 异步保存排班版本，成功后提示并刷新页面。 */
    private void persistVersion(final ScheduleVersion version, final String toast) {
        io.execute(() -> {
            db.scheduleDao().updateVersion(version);
            runOnUiThread(() -> {
                Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
                loadData();
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
    }

    /** 从偏好设置和数据库加载偏移量、人员与版本，再刷新页面。 */
    private void loadData() {
        // 每次进入页面或返回时都重新读取偏移量，保证与首页一致。
        offsetM = prefs.getInt("offset_m", 0);
        offsetW = prefs.getInt("offset_w", 0);
        offsetD = prefs.getInt("offset_d", 0);

        io.execute(() -> {
            List<Person> pList = db.personDao().getAll();
            List<ScheduleVersion> versions = db.scheduleDao().getAllVersions();
            long today = LocalDate.now().toEpochDay();

            ScheduleVersion tv = null;
            ScheduleVersion uv = null;
            for (ScheduleVersion v : versions) {
                if (v.effectiveFromEpochDay <= today) {
                    if (tv == null) tv = v;
                } else {
                    uv = v;
                }
            }

            final ScheduleVersion fTv = tv;
            final ScheduleVersion fUv = uv;
            runOnUiThread(() -> {
                people = pList;
                allVersions = versions;
                todayVersion = fTv;
                upcomingVersion = fUv;
                renderRuleCard();
                renderPreview();
            });
        });
    }

    /** 按当前版本和待生效版本刷新规则卡片与删除入口。 */
    private void renderRuleCard() {
        boolean hasAnyRule = todayVersion != null || upcomingVersion != null;
        btnDeleteRule.setVisibility(hasAnyRule ? View.VISIBLE : View.GONE);

        if (todayVersion != null) {
            // 自定义公式优先显示备注，长文本由跑马灯滚动。
            tvRuleSummary.setText(displayName(todayVersion) + " · " + people.size() + " 人");
            String detail = "生效日期：" + LocalDate.ofEpochDay(todayVersion.effectiveFromEpochDay);
            if (upcomingVersion != null) {
                detail += "\n下一个规则将于 "
                        + LocalDate.ofEpochDay(upcomingVersion.effectiveFromEpochDay) + " 生效";
            }
            tvRuleDetail.setText(detail);

            if (todayVersion.mode == ScheduleMode.CUSTOM && todayVersion.extraParams != null) {
                tvCopyHint.setVisibility(View.VISIBLE);
                View.OnClickListener showDialogAction = v -> showFormulaDialog(todayVersion);
                tvCopyHint.setOnClickListener(showDialogAction);
                tvRuleSummary.setOnClickListener(showDialogAction);
            } else {
                tvCopyHint.setVisibility(View.GONE);
                tvRuleSummary.setOnClickListener(null);
            }

        } else if (upcomingVersion != null) {
            tvRuleSummary.setText("待生效：" + displayName(upcomingVersion)
                    + " · " + people.size() + " 人");
            tvRuleDetail.setText(LocalDate.ofEpochDay(upcomingVersion.effectiveFromEpochDay) + " 起生效");
            tvCopyHint.setVisibility(View.GONE);
            tvRuleSummary.setOnClickListener(null);
        } else {
            tvRuleSummary.setText("还没有设置排班规则");
            tvRuleDetail.setText("点击“编辑规则”开始设置");
            tvCopyHint.setVisibility(View.GONE);
            tvRuleSummary.setOnClickListener(null);
        }
    }

    /** 自定义公式优先显示备注；没有备注就回落到默认名称。 */
    private String displayName(ScheduleVersion v) {
        if (v.mode != ScheduleMode.CUSTOM) return "通用每周轮转";
        String remark = v.formulaRemark;
        if (remark != null && !remark.trim().isEmpty()) return remark.trim();
        return "自定义公式";
    }

    /** 弹窗查看公式，支持单击编辑、双击复制与从公式库替换。 */
    private void showFormulaDialog(final ScheduleVersion version) {
        View view = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_view_formula, null);
        final android.widget.EditText et = view.findViewById(R.id.et_formula_content);
        final TextView tvHint = view.findViewById(R.id.tv_formula_hint);
        com.google.android.material.button.MaterialButton btnClose = view.findViewById(R.id.btn_close);
        com.google.android.material.button.MaterialButton btnSave = view.findViewById(R.id.btn_save);
        com.google.android.material.button.MaterialButton btnReplace = view.findViewById(R.id.btn_replace);

        formulaVersion = version;
        formulaEdit = et;
        et.setText(version.extraParams);
        setFormulaEditable(et, false);

        formulaDialog = new com.eafnv.callforward.ui.DutyDialogBuilder(this)
                .setTitle("当前排班公式")
                .setView(view)
                .setPositiveButton(null, null)
                .setNegativeButton(null, null)
                .create();
        formulaDialog.show();

        // 单击进入编辑，双击复制。
        final long[] lastClick = {0};
        et.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (now - lastClick[0] < 300) {
                copyFormula(et.getText().toString());
                lastClick[0] = 0;
            } else {
                lastClick[0] = now;
                setFormulaEditable(et, true);
                et.requestFocus();
                et.setSelection(et.getText().length());
                tvHint.setText("编辑中 · 改完点击保存生效");
            }
        });

        btnClose.setOnClickListener(v -> formulaDialog.dismiss());

        btnSave.setOnClickListener(v -> {
            String expr = et.getText().toString().trim();
            if (expr.isEmpty()) {
                Toast.makeText(this, "公式不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            String error = ScheduleEngine.validateFormula(expr, Math.max(1, people.size()));
            if (error != null) {
                showFormulaError(error);
                return;
            }
            version.extraParams = expr;
            formulaDialog.dismiss();
            persistVersion(version, "公式已保存");
        });

        btnReplace.setOnClickListener(v -> {
            Intent intent = new Intent(this, FormulaActivity.class);
            intent.putExtra(FormulaActivity.EXTRA_PICK, true);
            formulaPicker.launch(intent);
        });
    }

    /** 切换公式编辑框的只读状态，退出编辑时收起键盘。 */
    private void setFormulaEditable(android.widget.EditText et, boolean editable) {
        et.setFocusable(editable);
        et.setFocusableInTouchMode(editable);
        et.setCursorVisible(editable);
        if (!editable) {
            et.clearFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
        }
    }

    /** 复制公式到剪贴板并提示。 */
    private void copyFormula(String text) {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("DutyCall Formula", text));
        Toast.makeText(this, "公式已复制", Toast.LENGTH_SHORT).show();
    }

    /** 用可滚动文本展示公式解析错误。 */
    private void showFormulaError(String errorMessage) {
        TextView tv = new TextView(this);
        tv.setText("公式解析失败：\n\n" + errorMessage);
        tv.setPadding(60, 40, 60, 0);
        tv.setTextSize(14);
        tv.setTextColor(0xFFBA1A1A);
        tv.setLineSpacing(2f, 1f);

        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.addView(tv);

        new com.eafnv.callforward.ui.DutyDialogBuilder(this)
                .setTitle("公式有误")
                .setView(scrollView)
                .setPositiveButton("返回修改", null)
                .show();
    }

    /**
     * 异步计算未来 14 天的值班人并刷新预览列表。
     *
     * 临时换人优先于规则计算；没有匹配到人员时跳过该天。
     */
    private void renderPreview() {
        final List<Person> peopleSnapshot = new ArrayList<>(people);
        final List<ScheduleVersion> versionsSnapshot = new ArrayList<>(allVersions);

        if (peopleSnapshot.isEmpty()) {
            adapter.submit(new ArrayList<>());
            showEmptyState(true);
            return;
        }

        io.execute(() -> {
            List<SchedulePreviewAdapter.Row> rows = new ArrayList<>();
            LocalDate start = LocalDate.now();
            for (int i = 0; i < 14; i++) {
                LocalDate date = start.plusDays(i);
                long day = date.toEpochDay();

                ScheduleVersion v = null;
                for (ScheduleVersion ver : versionsSnapshot) {
                    if (ver.effectiveFromEpochDay <= day) {
                        v = ver;
                        break;
                    }
                }

                DayOverride override = db.scheduleDao().findOverride(day);

                Person p = null;
                if (v != null) {
                    p = ScheduleEngine.resolve(peopleSnapshot, v, override, date, offsetM, offsetW, offsetD);
                } else if (override != null) {
                    for (Person person : peopleSnapshot) {
                        if (person.id == override.personId) { p = person; break; }
                    }
                }

                if (p == null) continue;

                SchedulePreviewAdapter.Row row = new SchedulePreviewAdapter.Row();
                row.date = date;
                row.person = p;
                row.isOverride = override != null;
                row.isToday = i == 0;
                rows.add(row);
            }
            runOnUiThread(() -> {
                adapter.submit(rows);
                showEmptyState(rows.isEmpty());
            });
        });
    }

    /** 切换预览卡片与空状态的显示。 */
    private void showEmptyState(boolean empty) {
        tvPreviewEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        tvPreviewTitle.setVisibility(empty ? View.GONE : View.VISIBLE);
        tvPreviewHint.setVisibility(empty ? View.GONE : View.VISIBLE);
        recyclerPreview.setVisibility(empty ? View.GONE : View.VISIBLE);

        // 列表为空时，把卡片外壳也一起隐藏。
        cardPreview.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    /** 二次确认后清空全部排班版本与临时换人记录。 */
    private void confirmDeleteAll() {
        if (todayVersion == null && upcomingVersion == null) {
            Toast.makeText(this, "当前没有排班规则", Toast.LENGTH_SHORT).show();
            return;
        }

        android.view.View view = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_confirm, null);
        android.widget.TextView tvMessage = view.findViewById(R.id.tv_message);
        com.google.android.material.button.MaterialButton btnCancel = view.findViewById(R.id.btn_cancel);
        com.google.android.material.button.MaterialButton btnConfirm = view.findViewById(R.id.btn_confirm);

        tvMessage.setText("将清空所有排班版本和临时换人记录，此操作不可恢复。");

        com.google.android.material.dialog.MaterialAlertDialogBuilder builder =
                new com.eafnv.callforward.ui.DutyDialogBuilder(this)
                        .setTitle("删除所有排班规则")
                        .setView(view)
                        .setPositiveButton(null, null)
                        .setNegativeButton(null, null);

        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.show();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            dialog.dismiss();
            io.execute(() -> {
                db.scheduleDao().deleteAllOverrides();
                db.scheduleDao().deleteAllVersions();
                runOnUiThread(() -> {
                    Toast.makeText(this, "已清空排班规则", Toast.LENGTH_SHORT).show();
                    loadData();
                });
            });
        });
    }

    /** 为某一天选择临时换人，或恢复为规则计算的结果。 */
    private void showOverrideDialog(SchedulePreviewAdapter.Row row) {
        if (people.isEmpty()) return;

        android.view.View view = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_single_choice, null);
        android.widget.RadioGroup rg = view.findViewById(R.id.rg_choices);
        com.google.android.material.button.MaterialButton btnCancel = view.findViewById(R.id.btn_cancel);
        com.google.android.material.button.MaterialButton btnConfirm = view.findViewById(R.id.btn_confirm);

        int checkedId = -1;
        for (int i = 0; i < people.size() + 1; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            // 控件编号不能为 0（0 等于 View.NO_ID，单选组会判定为未选中）。
            rb.setId(i + 1);
            rb.setTextSize(16);
            rb.setPadding(0, 24, 0, 24);
            if (i == 0) {
                rb.setText("清除临时换人（恢复规则）");
            } else {
                rb.setText(people.get(i - 1).name);
                if (row.person != null && people.get(i - 1).id == row.person.id) {
                    checkedId = i + 1;
                }
            }
            rg.addView(rb);
        }
        if (checkedId != -1) rg.check(checkedId);

        com.google.android.material.dialog.MaterialAlertDialogBuilder builder =
                new com.eafnv.callforward.ui.DutyDialogBuilder(this)
                        .setTitle(row.date + " · 临时换人")
                        .setView(view)
                        .setPositiveButton(null, null)
                        .setNegativeButton(null, null);

        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.show();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            int selectedId = rg.getCheckedRadioButtonId();
            if (selectedId != -1) {
                dialog.dismiss();
                final int index = selectedId - 1;
                io.execute(() -> {
                    if (index == 0) {
                        db.scheduleDao().deleteOverride(row.date.toEpochDay());
                    } else {
                        DayOverride o = new DayOverride();
                        o.epochDay = row.date.toEpochDay();
                        o.personId = people.get(index - 1).id;
                        db.scheduleDao().upsertOverride(o);
                    }
                    runOnUiThread(this::renderPreview);
                });
            }
        });
    }

    /** 返回排班模式对应的展示名称。 */
    private String modeName(int mode) {
        switch (mode) {
            case ScheduleMode.CUSTOM: return "自定义公式";
            default: return "通用每周轮转";
        }
    }
}