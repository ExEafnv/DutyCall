package com.eafnv.callforward.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eafnv.callforward.R;
import com.eafnv.callforward.data.AppDatabase;
import com.eafnv.callforward.data.Formula;
import com.eafnv.callforward.data.ScheduleEngine;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 公式管理页，支持新建、编辑、删除与选择公式。
 *
 * 普通模式下管理公式库；选择模式（EXTRA_PICK=true）下点击条目即回传调用方。
 */
public class FormulaActivity extends AppCompatActivity {

    public static final String EXTRA_PICK = "extra_pick";
    public static final String EXTRA_ID = "extra_id";
    public static final String EXTRA_NAME = "extra_name";
    public static final String EXTRA_EXPR = "extra_expr";

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private AppDatabase db;
    private FormulaAdapter adapter;
    private TextView tvEmpty;

    private boolean pickMode;
    private List<Formula> formulas = new ArrayList<>();
    private int peopleCount = 1;

    /** 初始化列表、选择模式与公式编辑入口。 */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_formula);

        pickMode = getIntent().getBooleanExtra(EXTRA_PICK, false);
        db = AppDatabase.get(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(pickMode ? "选择公式" : "公式管理");
        toolbar.setNavigationOnClickListener(v -> finish());

        tvEmpty = findViewById(R.id.tv_empty);
        RecyclerView recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));

        adapter = new FormulaAdapter();
        adapter.setShowDelete(!pickMode);
        adapter.setListener(new FormulaAdapter.Listener() {
            @Override
            public void onClick(Formula formula) {
                if (pickMode) {
                    returnResult(formula);
                } else {
                    showEditDialog(formula);
                }
            }

            @Override
            public void onDelete(Formula formula) {
                confirmDelete(formula);
            }
        });
        recycler.setAdapter(adapter);

        View fab = findViewById(R.id.fab_add);
        fab.setOnClickListener(v -> showEditDialog(null));
        if (pickMode) fab.setVisibility(View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
    }

    /** 异步加载公式与人员数量，刷新列表和空状态。 */
    private void loadData() {
        io.execute(() -> {
            List<Formula> list = db.formulaDao().getAll();
            int count = db.personDao().count();
            runOnUiThread(() -> {
                formulas = list;
                peopleCount = Math.max(1, count);
                adapter.submit(list);
                tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    /** 把选中公式回传给调用方并结束页面。 */
    private void returnResult(Formula formula) {
        Intent data = new Intent();
        data.putExtra(EXTRA_ID, formula.id);
        data.putExtra(EXTRA_NAME, formula.name);
        data.putExtra(EXTRA_EXPR, formula.expression);
        setResult(RESULT_OK, data);
        finish();
    }

    /** 新建或编辑公式，保存前校验名称、表达式与语法。 */
    private void showEditDialog(final Formula existing) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_formula_edit, null);
        final TextInputLayout tilName = view.findViewById(R.id.til_formula_name);
        final TextInputLayout tilExpr = view.findViewById(R.id.til_formula_expr);
        final TextInputEditText etName = view.findViewById(R.id.et_formula_name);
        final TextInputEditText etExpr = view.findViewById(R.id.et_formula_expr);
        MaterialButton btnCancel = view.findViewById(R.id.btn_cancel);
        MaterialButton btnSave = view.findViewById(R.id.btn_save);

        if (existing != null) {
            etName.setText(existing.name);
            etExpr.setText(existing.expression);
        }

        final AlertDialog dialog = new com.eafnv.callforward.ui.DutyDialogBuilder(this)
                .setTitle(existing == null ? "新建公式" : "编辑公式")
                .setView(view)
                .setPositiveButton(null, null)
                .setNegativeButton(null, null)
                .create();
        dialog.show();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnSave.setOnClickListener(v -> {
            tilName.setError(null);
            tilExpr.setError(null);

            String name = text(etName);
            String expr = text(etExpr);

            if (name.isEmpty()) {
                tilName.setError("请填写备注，方便在排班里认出这条公式");
                return;
            }
            if (expr.isEmpty()) {
                tilExpr.setError("公式不能为空");
                return;
            }
            String error = ScheduleEngine.validateFormula(expr, peopleCount);
            if (error != null) {
                tilExpr.setError(error);
                return;
            }

            dialog.dismiss();
            io.execute(() -> {
                if (existing == null) {
                    db.formulaDao().insert(new Formula(name, expr, System.currentTimeMillis()));
                } else {
                    existing.name = name;
                    existing.expression = expr;
                    db.formulaDao().update(existing);
                }
                runOnUiThread(() -> {
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                    loadData();
                });
            });
        });
    }

    /** 二次确认后删除公式。 */
    private void confirmDelete(final Formula formula) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_confirm, null);
        TextView tvMessage = view.findViewById(R.id.tv_message);
        MaterialButton btnCancel = view.findViewById(R.id.btn_cancel);
        MaterialButton btnConfirm = view.findViewById(R.id.btn_confirm);

        tvMessage.setText("将删除公式「" + (formula.name == null ? "" : formula.name)
                + "」。已用过这条公式的排班规则不受影响。");

        final AlertDialog dialog = new com.eafnv.callforward.ui.DutyDialogBuilder(this)
                .setTitle("删除公式")
                .setView(view)
                .setPositiveButton(null, null)
                .setNegativeButton(null, null)
                .create();
        dialog.show();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            dialog.dismiss();
            io.execute(() -> {
                db.formulaDao().delete(formula);
                runOnUiThread(this::loadData);
            });
        });
    }

    private static String text(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }
}
