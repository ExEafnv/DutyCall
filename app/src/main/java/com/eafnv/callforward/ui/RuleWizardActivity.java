package com.eafnv.callforward.ui;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eafnv.callforward.R;
import com.eafnv.callforward.data.AppDatabase;
import com.eafnv.callforward.data.Person;
import com.eafnv.callforward.data.ScheduleEngine;
import com.eafnv.callforward.data.ScheduleMode;
import com.eafnv.callforward.data.ScheduleVersion;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RuleWizardActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private AppDatabase db;
    private List<Person> people = new ArrayList<>();

    private int step = 0; // 0..3
    private long anchorPersonId = -1;
    private LocalDate effectiveDate;

    private SchedulePreviewAdapter previewAdapter;

    private LinearLayout stepMode, stepAnchor, stepEffective, stepPreview;
    private TextView tvStepTitle, tvStepHint, tvAnchorValue, tvEffectiveValue, tvFairness;
    private RadioGroup rgMode;
    private LinearLayout layoutCustomParams;
    private com.google.android.material.textfield.TextInputEditText etFormula;
    private Button btnPrev, btnNext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rule_wizard);

        // 修复状态栏
        boolean isDarkMode = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        androidx.core.view.WindowInsetsControllerCompat controller = androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(!isDarkMode);
            controller.setAppearanceLightNavigationBars(!isDarkMode);
        }

        db = AppDatabase.get(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        stepMode = findViewById(R.id.step_mode);
        stepAnchor = findViewById(R.id.step_anchor);
        stepEffective = findViewById(R.id.step_effective);
        stepPreview = findViewById(R.id.step_preview);
        tvStepTitle = findViewById(R.id.tv_step_title);
        tvStepHint = findViewById(R.id.tv_step_hint);
        tvAnchorValue = findViewById(R.id.tv_anchor_value);
        tvEffectiveValue = findViewById(R.id.tv_effective_value);
        tvFairness = findViewById(R.id.tv_fairness);
        rgMode = findViewById(R.id.rg_mode);
        layoutCustomParams = findViewById(R.id.layout_custom_params);
        etFormula = findViewById(R.id.et_formula);
        etFormula.setText("mod(d, N) + 1");

        // 绑定说明弹窗
        findViewById(R.id.tv_formula_help).setOnClickListener(v -> {
            View helpView = LayoutInflater.from(this).inflate(R.layout.dialog_formula_help, null);
            MaterialButton btnClose = helpView.findViewById(R.id.btn_close_help);

            AlertDialog helpDialog = new MaterialAlertDialogBuilder(this)
                    .setTitle("公式语法指南")
                    .setView(helpView)
                    .setPositiveButton(null, null)
                    .setNegativeButton(null, null)
                    .create();

            helpDialog.show();
            btnClose.setOnClickListener(c -> helpDialog.dismiss());
        });

        btnPrev = findViewById(R.id.btn_prev);
        btnNext = findViewById(R.id.btn_next);

        rgMode.setOnCheckedChangeListener((g, id) -> {
            boolean isCustom = (id == R.id.rb_custom);
            layoutCustomParams.setVisibility(isCustom ? View.VISIBLE : View.GONE);

            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);

            // 如果选中了自定义公式，自动全选默认公式，并延迟弹出键盘
            if (isCustom) {
                etFormula.requestFocus();
                etFormula.selectAll();
                // 延迟 200 毫秒，等待布局渲染完成后弹出键盘
                etFormula.postDelayed(() -> {
                    if (imm != null) {
                        imm.showSoftInput(etFormula, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                    }
                }, 200);
            } else {
                // 如果切回其他模式，把键盘收起来
                if (imm != null) {
                    imm.hideSoftInputFromWindow(etFormula.getWindowToken(), 0);
                }
            }
        });

        findViewById(R.id.btn_pick_anchor).setOnClickListener(v -> pickAnchor());
        findViewById(R.id.btn_pick_effective).setOnClickListener(v -> pickEffective());

        RecyclerView recycler = findViewById(R.id.recycler_preview);
        previewAdapter = new SchedulePreviewAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(previewAdapter);

        btnPrev.setOnClickListener(v -> { if (step > 0) { step--; renderStep(); } });
        btnNext.setOnClickListener(v -> onNext());

        effectiveDate = LocalDate.now();
        loadPeople();
    }

    private void loadPeople() {
        io.execute(() -> {
            List<Person> list = db.personDao().getAll();
            runOnUiThread(() -> {
                people = list;
                if (people.isEmpty()) {
                    Toast.makeText(this, "请先在人员管理里添加人员", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                renderStep();
            });
        });
    }

    private void renderStep() {
        stepMode.setVisibility(step == 0 ? View.VISIBLE : View.GONE);
        stepAnchor.setVisibility(step == 1 ? View.VISIBLE : View.GONE);
        stepEffective.setVisibility(step == 2 ? View.VISIBLE : View.GONE);
        stepPreview.setVisibility(step == 3 ? View.VISIBLE : View.GONE);

        btnPrev.setEnabled(step > 0);
        btnNext.setText(step == 3 ? "保存规则" : "下一步");

        switch (step) {
            case 0:
                tvStepTitle.setText("第 1 步：选择轮转模式");
                tvStepHint.setText("两种模式互斥，选一个最符合班组习惯的。");
                break;
            case 1:
                tvStepTitle.setText("第 2 步：设置锚点");
                tvStepHint.setText("选择从谁开始。之后按名单顺序往后排。");
                tvAnchorValue.setText(anchorPersonId < 0 ? "未选择" : nameOf(anchorPersonId));
                break;
            case 2:
                tvStepTitle.setText("第 3 步：选择生效日期");
                tvStepHint.setText("从这一天起按新规则排班，历史日期不受影响。");
                tvEffectiveValue.setText(effectiveDate.toString());
                break;
            case 3:
                tvStepTitle.setText("第 4 步：预览并保存");
                tvStepHint.setText("下面是未来 28 天的排班，确认后保存。");
                refreshPreview();
                break;
        }
    }

    private void pickAnchor() {
        android.view.View view = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_single_choice, null);
        android.widget.RadioGroup rg = view.findViewById(R.id.rg_choices);
        MaterialButton btnCancel = view.findViewById(R.id.btn_cancel);
        MaterialButton btnConfirm = view.findViewById(R.id.btn_confirm);

        int checkedId = -1;
        for (int i = 0; i < people.size(); i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(i);
            rb.setTextSize(16);
            rb.setPadding(0, 24, 0, 24);
            rb.setText(people.get(i).name);
            if (people.get(i).id == anchorPersonId) {
                checkedId = i;
            }
            rg.addView(rb);
        }
        if (checkedId != -1) rg.check(checkedId);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("从谁开始")
                .setView(view)
                .setPositiveButton(null, null)
                .setNegativeButton(null, null)
                .create();
        dialog.show();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            int selectedId = rg.getCheckedRadioButtonId();
            if (selectedId != -1) {
                dialog.dismiss();
                anchorPersonId = people.get(selectedId).id;
                tvAnchorValue.setText(people.get(selectedId).name);
            }
        });
    }

    private void pickEffective() {
        new DatePickerDialog(this, (view, y, m, d) -> {
            effectiveDate = LocalDate.of(y, m + 1, d);
            tvEffectiveValue.setText(effectiveDate.toString());
        }, effectiveDate.getYear(), effectiveDate.getMonthValue() - 1, effectiveDate.getDayOfMonth()).show();
    }

    private void onNext() {
        if (!validateCurrent()) return;
        if (step < 3) {
            step++;
            renderStep();
        } else {
            save();
        }
    }

    private boolean validateCurrent() {
        if (step == 0) {
            if (rgMode.getCheckedRadioButtonId() == R.id.rb_custom) {
                String formula = etFormula.getText().toString().trim();
                if (TextUtils.isEmpty(formula)) {
                    Toast.makeText(this, "自定义公式不能为空", Toast.LENGTH_SHORT).show();
                    return false;
                }

                // 调用引擎进行校验
                String errorMsg = ScheduleEngine.validateFormula(formula, people.size());
                if (errorMsg != null) {
                    showErrorDialog(errorMsg);
                    return false; // 拦截，不进入下一步
                }
            }
            return true;
        }
        if (step == 1) {
            if (anchorPersonId < 0) {
                Toast.makeText(this, "请选择锚点人员", Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        }
        return true;
    }

    // 显示错误弹窗
    private void showErrorDialog(String errorMessage) {
        // 用一个带滚动的 TextView 来展示可能很长的报错
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText("公式解析失败：\n\n" + errorMessage);
        tv.setPadding(60, 40, 60, 0);
        tv.setTextSize(14);
        tv.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
        tv.setLineSpacing(2f, 1f);

        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.addView(tv);
        scrollView.setPadding(0, 0, 0, 20);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("公式有误")
                .setView(scrollView)
                .setPositiveButton("返回修改", null)
                .show();
    }

    private int selectedMode() {
        int id = rgMode.getCheckedRadioButtonId();
        if (id == R.id.rb_custom) return ScheduleMode.CUSTOM;
        return ScheduleMode.WEEKLY;
    }

    private String buildExtraParams() {
        if (selectedMode() == ScheduleMode.CUSTOM) {
            return etFormula.getText().toString().trim();
        }
        return null;
    }

    private void refreshPreview() {
        ScheduleVersion v = buildVersionForPreview();
        List<SchedulePreviewAdapter.Row> rows = new ArrayList<>();
        LocalDate start = LocalDate.now();
        for (int i = 0; i < 28; i++) {
            LocalDate date = start.plusDays(i);
            SchedulePreviewAdapter.Row row = new SchedulePreviewAdapter.Row();
            row.date = date;
            row.person = ScheduleEngine.computeByRule(people, v, date);
            row.isToday = i == 0;
            rows.add(row);
        }
        previewAdapter.submit(rows);

        int[] count = new int[people.size()];
        for (SchedulePreviewAdapter.Row r : rows) {
            if (r.person != null) {
                int idx = ScheduleEngine.indexOf(people, r.person.id);
                if (idx >= 0) count[idx]++;
            }
        }
        int min = Integer.MAX_VALUE, max = 0;
        for (int c : count) { if (c < min) min = c; if (c > max) max = c; }
        StringBuilder sb = new StringBuilder("28 天内值班天数：");
        sb.append("最少 ").append(min).append(" 天，最多 ").append(max).append(" 天");
        if (max - min > 2) sb.append("（差异较大，请确认规则）");
        tvFairness.setText(sb.toString());
    }

    private ScheduleVersion buildVersionForPreview() {
        ScheduleVersion v = new ScheduleVersion();
        v.mode = selectedMode();
        v.anchorMondayEpochDay = LocalDate.now().with(DayOfWeek.MONDAY).toEpochDay();
        v.anchorPersonId = anchorPersonId;
        v.extraParams = buildExtraParams();
        return v;
    }

    private void save() {
        ScheduleVersion v = buildVersionForPreview();
        v.effectiveFromEpochDay = effectiveDate.toEpochDay();
        v.createdAt = System.currentTimeMillis();

        io.execute(() -> {
            db.scheduleDao().insertVersion(v);
            runOnUiThread(() -> {
                Toast.makeText(this, "规则已保存", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            });
        });
    }

    private String nameOf(long id) {
        int idx = ScheduleEngine.indexOf(people, id);
        return idx < 0 ? "—" : people.get(idx).name;
    }
}