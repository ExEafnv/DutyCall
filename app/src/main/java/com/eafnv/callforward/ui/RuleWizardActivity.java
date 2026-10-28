package com.eafnv.callforward.ui;

import android.app.DatePickerDialog;
import android.content.SharedPreferences;
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
import com.eafnv.callforward.data.Formula;
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

/**
 * 新建排班规则的四步向导：选择模式、设置锚点、选择生效日期、预览并保存。
 *
 * 预览复用排班页的适配器与计算逻辑，保证两处结果一致。
 */
public class RuleWizardActivity extends AppCompatActivity {

    private static final String PREFS = "duty_prefs";

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private AppDatabase db;
    private SharedPreferences prefs;
    private List<Person> people = new ArrayList<>();

    // 当前步骤，取值 0 到 3。
    private int step = 0;
    private long anchorPersonId = -1;
    private LocalDate effectiveDate;

    private SchedulePreviewAdapter previewAdapter;

    private LinearLayout stepMode, stepAnchor, stepEffective, stepPreview;
    private TextView tvStepTitle, tvStepHint, tvAnchorValue, tvEffectiveValue, tvFairness;
    private RadioGroup rgMode;
    private LinearLayout layoutCustomParams;
    private LinearLayout layoutPickFormula;
    private TextView tvSelectedFormulaName, tvSelectedFormulaExpr;
    private com.google.android.material.textfield.TextInputEditText etFormula;
    private com.google.android.material.textfield.TextInputEditText etFormulaName;
    private Button btnPrev, btnNext;

    // 已选中的公式库条目。
    private long pickedFormulaId = 0;
    private String pickedFormulaName = "";
    private String pickedFormulaExpr = "";

    private androidx.activity.result.ActivityResultLauncher<android.content.Intent> formulaPicker;

    /** 初始化向导页面、步骤控件与公式选择器。 */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rule_wizard);

        // ===== 状态栏 =====
        boolean isDarkMode = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        androidx.core.view.WindowInsetsControllerCompat controller = androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(!isDarkMode);
            controller.setAppearanceLightNavigationBars(!isDarkMode);
        }

        db = AppDatabase.get(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

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
        layoutPickFormula = findViewById(R.id.layout_pick_formula);
        tvSelectedFormulaName = findViewById(R.id.tv_selected_formula_name);
        tvSelectedFormulaExpr = findViewById(R.id.tv_selected_formula_expr);
        etFormula = findViewById(R.id.et_formula);
        etFormulaName = findViewById(R.id.et_formula_name);
        etFormula.setText("mod(d, N) + 1");

        // 公式库返回后记录所选条目并刷新展示。
        formulaPicker = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    pickedFormulaId = result.getData().getLongExtra(FormulaActivity.EXTRA_ID, 0);
                    pickedFormulaName = result.getData().getStringExtra(FormulaActivity.EXTRA_NAME);
                    pickedFormulaExpr = result.getData().getStringExtra(FormulaActivity.EXTRA_EXPR);
                    renderPickedFormula();
                });

        findViewById(R.id.row_pick_formula).setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(this, FormulaActivity.class);
            intent.putExtra(FormulaActivity.EXTRA_PICK, true);
            formulaPicker.launch(intent);
        });

        // 绑定公式语法帮助弹窗。
        findViewById(R.id.tv_formula_help).setOnClickListener(v -> {
            View helpView = LayoutInflater.from(this).inflate(R.layout.dialog_formula_help, null);
            MaterialButton btnClose = helpView.findViewById(R.id.btn_close_help);

            AlertDialog helpDialog = new com.eafnv.callforward.ui.DutyDialogBuilder(this)
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
            boolean isNew = (id == R.id.rb_formula_new);
            boolean isPick = (id == R.id.rb_formula_pick);
            layoutCustomParams.setVisibility(isNew ? View.VISIBLE : View.GONE);
            layoutPickFormula.setVisibility(isPick ? View.VISIBLE : View.GONE);

            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);

            // 新建公式时自动选中默认表达式并弹出键盘。
            if (isNew) {
                etFormula.requestFocus();
                etFormula.selectAll();
                etFormula.postDelayed(() -> {
                    if (imm != null) {
                        imm.showSoftInput(etFormula, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                    }
                }, 200);
            } else {
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

        btnPrev.setOnClickListener(v -> {
            if (step > 0) {
                step--;
                renderStep();
            } else {
                finish();
            }
        });
        btnNext.setOnClickListener(v -> onNext());

        effectiveDate = LocalDate.now();
        loadPeople();
    }

    /** 异步加载人员，名单为空时提示并退出向导。 */
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

    /** 按当前步骤切换面板、按钮文案与标题提示。 */
    private void renderStep() {
        stepMode.setVisibility(step == 0 ? View.VISIBLE : View.GONE);
        stepAnchor.setVisibility(step == 1 ? View.VISIBLE : View.GONE);
        stepEffective.setVisibility(step == 2 ? View.VISIBLE : View.GONE);
        stepPreview.setVisibility(step == 3 ? View.VISIBLE : View.GONE);

        // 第 1 步没有“上一步”，做成“取消”直接退出，避免出现禁用态按钮。
        btnPrev.setText(step == 0 ? "取消" : "上一步");
        btnNext.setText(step == 3 ? "保存规则" : "下一步");

        switch (step) {
            case 0:
                tvStepTitle.setText("第 1 步：选择轮转模式");
                tvStepHint.setText("三种方式互斥，选一个最符合班组习惯的。");
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

    /** 从人员列表中选择锚点。 */
    private void pickAnchor() {
        android.view.View view = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_single_choice, null);
        android.widget.RadioGroup rg = view.findViewById(R.id.rg_choices);
        MaterialButton btnCancel = view.findViewById(R.id.btn_cancel);
        MaterialButton btnConfirm = view.findViewById(R.id.btn_confirm);

        int checkedId = -1;
        for (int i = 0; i < people.size(); i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            // 注意：控件编号不能为 0（0 等于 View.NO_ID，单选组会判定为“未选中”）。
            rb.setId(i + 1);
            rb.setTextSize(16);
            rb.setPadding(0, 24, 0, 24);
            rb.setText(people.get(i).name);
            if (people.get(i).id == anchorPersonId) {
                checkedId = i + 1;
            }
            rg.addView(rb);
        }
        if (checkedId != -1) rg.check(checkedId);

        AlertDialog dialog = new com.eafnv.callforward.ui.DutyDialogBuilder(this)
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
                int index = selectedId - 1;
                anchorPersonId = people.get(index).id;
                tvAnchorValue.setText(people.get(index).name);
            }
        });
    }

    /** 选择规则生效日期。 */
    private void pickEffective() {
        new DatePickerDialog(this, (view, y, m, d) -> {
            effectiveDate = LocalDate.of(y, m + 1, d);
            tvEffectiveValue.setText(effectiveDate.toString());
        }, effectiveDate.getYear(), effectiveDate.getMonthValue() - 1, effectiveDate.getDayOfMonth()).show();
    }

    /** 校验当前步骤，通过后进入下一步或保存。 */
    private void onNext() {
        if (!validateCurrent()) return;
        if (step < 3) {
            step++;
            renderStep();
        } else {
            save();
        }
    }

    /** 校验当前步骤的输入，返回是否允许前进。 */
    private boolean validateCurrent() {
        if (step == 0) {
            int checked = rgMode.getCheckedRadioButtonId();
            if (checked == R.id.rb_formula_pick) {
                if (pickedFormulaId <= 0) {
                    Toast.makeText(this, "请先选择一条公式", Toast.LENGTH_SHORT).show();
                    return false;
                }
                String error = ScheduleEngine.validateFormula(pickedFormulaExpr, people.size());
                if (error != null) {
                    showErrorDialog(error);
                    return false;
                }
            } else if (checked == R.id.rb_formula_new) {
                String formula = etFormula.getText().toString().trim();
                if (TextUtils.isEmpty(formula)) {
                    Toast.makeText(this, "自定义公式不能为空", Toast.LENGTH_SHORT).show();
                    return false;
                }
                String errorMsg = ScheduleEngine.validateFormula(formula, people.size());
                if (errorMsg != null) {
                    showErrorDialog(errorMsg);
                    return false;
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

    /** 展示公式解析错误详情。 */
    private void showErrorDialog(String errorMessage) {
        // 用带滚动的 TextView 展示可能很长的报错。
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText("公式解析失败：\n\n" + errorMessage);
        tv.setPadding(60, 40, 60, 0);
        tv.setTextSize(14);
        tv.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
        tv.setLineSpacing(2f, 1f);

        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.addView(tv);
        scrollView.setPadding(0, 0, 0, 20);

        new com.eafnv.callforward.ui.DutyDialogBuilder(this)
                .setTitle("公式有误")
                .setView(scrollView)
                .setPositiveButton("返回修改", null)
                .show();
    }

    /** 返回当前选择的排班模式。 */
    private int selectedMode() {
        int id = rgMode.getCheckedRadioButtonId();
        if (id == R.id.rb_weekly) return ScheduleMode.WEEKLY;
        return ScheduleMode.CUSTOM;
    }

    /** 返回当前模式下要保存的公式表达式。 */
    private String buildExtraParams() {
        if (selectedMode() == ScheduleMode.CUSTOM) {
            if (rgMode.getCheckedRadioButtonId() == R.id.rb_formula_pick) {
                return pickedFormulaExpr;
            }
            return etFormula.getText().toString().trim();
        }
        return null;
    }

    /** 刷新已选公式的名称与表达式展示。 */
    private void renderPickedFormula() {
        boolean has = pickedFormulaId > 0;
        tvSelectedFormulaName.setText(has
                ? (pickedFormulaName == null || pickedFormulaName.isEmpty() ? "未命名公式" : pickedFormulaName)
                : "未选择公式");
        tvSelectedFormulaExpr.setText(has && pickedFormulaExpr != null ? pickedFormulaExpr : "");
    }

    /** 刷新未来 28 天的预览，并统计每人的值班天数。 */
    private void refreshPreview() {
        // 每次刷新预览都读取最新偏移量，保证与首页、排班页一致。
        int offsetM = prefs.getInt("offset_m", 0);
        int offsetW = prefs.getInt("offset_w", 0);
        int offsetD = prefs.getInt("offset_d", 0);

        ScheduleVersion v = buildVersionForPreview();
        List<SchedulePreviewAdapter.Row> rows = new ArrayList<>();
        LocalDate start = LocalDate.now();
        for (int i = 0; i < 28; i++) {
            LocalDate date = start.plusDays(i);
            SchedulePreviewAdapter.Row row = new SchedulePreviewAdapter.Row();
            row.date = date;
            row.person = ScheduleEngine.computeByRule(people, v, date, offsetM, offsetW, offsetD);
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

    /** 构造仅用于预览的临时排班版本。 */
    private ScheduleVersion buildVersionForPreview() {
        ScheduleVersion v = new ScheduleVersion();
        v.mode = selectedMode();
        v.anchorMondayEpochDay = LocalDate.now().with(DayOfWeek.MONDAY).toEpochDay();
        v.anchorPersonId = anchorPersonId;
        v.extraParams = buildExtraParams();
        return v;
    }

    /** 保存排班版本，并把新建的自定义公式同步写入公式库。 */
    private void save() {
        ScheduleVersion v = buildVersionForPreview();
        v.effectiveFromEpochDay = effectiveDate.toEpochDay();
        v.createdAt = System.currentTimeMillis();

        final int checked = rgMode.getCheckedRadioButtonId();
        if (checked == R.id.rb_formula_pick) {
            v.formulaId = pickedFormulaId;
            v.formulaRemark = pickedFormulaName;
        } else if (checked == R.id.rb_formula_new) {
            v.formulaId = 0;
            v.formulaRemark = etFormulaName.getText().toString().trim();
        }

        io.execute(() -> {
            // 新建的公式顺手存进公式库，方便以后复用。
            if (checked == R.id.rb_formula_new
                    && v.formulaRemark != null && !v.formulaRemark.isEmpty()
                    && v.extraParams != null && !v.extraParams.isEmpty()) {
                db.formulaDao().insert(new Formula(v.formulaRemark, v.extraParams, System.currentTimeMillis()));
            }
            db.scheduleDao().insertVersion(v);
            runOnUiThread(() -> {
                Toast.makeText(this, "规则已保存", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            });
        });
    }

    /** 按人员编号查找姓名，找不到时返回占位符。 */
    private String nameOf(long id) {
        int idx = ScheduleEngine.indexOf(people, id);
        return idx < 0 ? "—" : people.get(idx).name;
    }
}