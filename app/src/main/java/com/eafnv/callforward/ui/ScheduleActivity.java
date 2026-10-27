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

public class ScheduleActivity extends AppCompatActivity {

    private static final String PREFS = "duty_prefs";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private AppDatabase db;
    private SharedPreferences prefs;
    private SchedulePreviewAdapter adapter;

    // 排班偏移量（每次 loadData 时从 prefs 重新读取）
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_schedule);

        // === 修复状态栏图标颜色 ===
        boolean isDarkMode = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        androidx.core.view.WindowInsetsControllerCompat controller =
                androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(!isDarkMode);
            controller.setAppearanceLightNavigationBars(!isDarkMode);
        }
        // =========================

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
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
    }

    private void loadData() {
        // 每次进页 / 返回都重新读取偏移量，保证和首页一致
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

    private void renderRuleCard() {
        boolean hasAnyRule = todayVersion != null || upcomingVersion != null;
        btnDeleteRule.setVisibility(hasAnyRule ? View.VISIBLE : View.GONE);

        android.widget.TextView tvCopyHint = findViewById(R.id.tv_copy_hint);

        if (todayVersion != null) {
            tvRuleSummary.setText(modeName(todayVersion.mode) + " · " + people.size() + " 人");
            String detail = "生效日期：" + LocalDate.ofEpochDay(todayVersion.effectiveFromEpochDay);
            if (upcomingVersion != null) {
                detail += "\n下一个规则将于 "
                        + LocalDate.ofEpochDay(upcomingVersion.effectiveFromEpochDay) + " 生效";
            }
            tvRuleDetail.setText(detail);

            if (todayVersion.mode == ScheduleMode.CUSTOM && todayVersion.extraParams != null) {
                tvCopyHint.setVisibility(View.VISIBLE);

                View.OnClickListener showDialogAction = v -> showFormulaDialog(todayVersion.extraParams);

                tvCopyHint.setOnClickListener(showDialogAction);
                tvRuleSummary.setOnClickListener(showDialogAction);
            } else {
                tvCopyHint.setVisibility(View.GONE);
                tvRuleSummary.setOnClickListener(null);
            }

        } else if (upcomingVersion != null) {
            tvRuleSummary.setText("待生效：" + modeName(upcomingVersion.mode)
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

    // 弹出显示公式的对话框
    private void showFormulaDialog(String formula) {
        android.view.View view = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_view_formula, null);
        android.widget.TextView tvContent = view.findViewById(R.id.tv_formula_content);
        com.google.android.material.button.MaterialButton btnClose = view.findViewById(R.id.btn_close);

        tvContent.setText(formula);

        androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("当前排班公式")
                .setView(view)
                .setPositiveButton(null, null)
                .setNegativeButton(null, null)
                .create();
        dialog.show();

        // 实现双击复制逻辑
        final long[] lastClickTime = {0};
        tvContent.setOnClickListener(v -> {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastClickTime[0] < 500) {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("DutyCall Formula", formula);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, "公式已复制", Toast.LENGTH_SHORT).show();
                }
            }
            lastClickTime[0] = currentTime;
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());
    }

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

    private void showEmptyState(boolean empty) {
        tvPreviewEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        tvPreviewTitle.setVisibility(empty ? View.GONE : View.VISIBLE);
        tvPreviewHint.setVisibility(empty ? View.GONE : View.VISIBLE);
        recyclerPreview.setVisibility(empty ? View.GONE : View.VISIBLE);

        // 核心修复：当列表为空时，把卡片外壳也一起隐藏
        cardPreview.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

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
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
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

    private void showOverrideDialog(SchedulePreviewAdapter.Row row) {
        if (people.isEmpty()) return;

        android.view.View view = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_single_choice, null);
        android.widget.RadioGroup rg = view.findViewById(R.id.rg_choices);
        com.google.android.material.button.MaterialButton btnCancel = view.findViewById(R.id.btn_cancel);
        com.google.android.material.button.MaterialButton btnConfirm = view.findViewById(R.id.btn_confirm);

        int checkedId = -1;
        for (int i = 0; i < people.size() + 1; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(i);
            rb.setTextSize(16);
            rb.setPadding(0, 24, 0, 24);
            if (i == 0) {
                rb.setText("清除临时换人（恢复规则）");
            } else {
                rb.setText(people.get(i - 1).name);
                if (row.person != null && people.get(i - 1).id == row.person.id) {
                    checkedId = i;
                }
            }
            rg.addView(rb);
        }
        if (checkedId != -1) rg.check(checkedId);

        com.google.android.material.dialog.MaterialAlertDialogBuilder builder =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
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
                io.execute(() -> {
                    if (selectedId == 0) {
                        db.scheduleDao().deleteOverride(row.date.toEpochDay());
                    } else {
                        DayOverride o = new DayOverride();
                        o.epochDay = row.date.toEpochDay();
                        o.personId = people.get(selectedId - 1).id;
                        db.scheduleDao().upsertOverride(o);
                    }
                    runOnUiThread(this::renderPreview);
                });
            }
        });
    }

    private String modeName(int mode) {
        switch (mode) {
            case ScheduleMode.CUSTOM: return "自定义公式";
            default: return "通用每周轮转";
        }
    }
}