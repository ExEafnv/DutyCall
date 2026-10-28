package com.eafnv.callforward.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eafnv.callforward.R;
import com.eafnv.callforward.data.AppDatabase;
import com.eafnv.callforward.data.DutyLog;
import com.eafnv.callforward.data.LogFolder;
import com.eafnv.callforward.data.Person;
import com.eafnv.callforward.mmi.AutoSwitchRunner;
import com.eafnv.callforward.util.LogSearch;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 日志管理页：按日期倒序浏览、搜索、长按多选后批量删除或归档。
 */
public class LogActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private AppDatabase db;
    private LogAdapter adapter;
    private MaterialToolbar toolbar;
    private TextView tvEmpty;
    private View selectionBar;
    private EditText etSearch;
    private View btnClearSearch;

    /** 全量日志（已带值班人与目录名），搜索时在内存里过滤。 */
    private final List<LogAdapter.Item> allItems = new ArrayList<>();
    private List<LogFolder> folders = new ArrayList<>();
    private String query = "";

    /** 防止输入过程中频繁过滤。 */
    private final Runnable applyFilterTask = this::applyFilter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        db = AppDatabase.get(this);

        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> {
            if (adapter != null && adapter.isSelectionMode()) {
                exitSelection();
            } else {
                finish();
            }
        });
        // 右上角进入归档视图
        toolbar.inflateMenu(R.menu.menu_log);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_archive) {
                startActivity(new Intent(this, LogArchiveActivity.class));
                return true;
            }
            return false;
        });

        tvEmpty = findViewById(R.id.tv_logs_empty);
        selectionBar = findViewById(R.id.selection_bar);
        etSearch = findViewById(R.id.et_search);
        btnClearSearch = findViewById(R.id.btn_clear_search);

        RecyclerView recycler = findViewById(R.id.recycler_logs);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LogAdapter();
        recycler.setAdapter(adapter);

        adapter.setListener(new LogAdapter.Listener() {
            @Override
            public void onClick(LogAdapter.Item item) {
                if (adapter.isSelectionMode()) {
                    adapter.toggle(item.log.epochDay);
                    updateSelectionUi();
                } else {
                    startActivity(LogEditActivity.intent(LogActivity.this, item.log.epochDay));
                }
            }

            @Override
            public void onLongClick(LogAdapter.Item item) {
                if (!adapter.isSelectionMode()) {
                    adapter.setSelectionMode(true);
                    adapter.toggle(item.log.epochDay);
                    updateSelectionUi();
                }
            }
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s == null ? "" : s.toString();
                btnClearSearch.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                handler.removeCallbacks(applyFilterTask);
                handler.postDelayed(applyFilterTask, 180);
            }
        });
        btnClearSearch.setOnClickListener(v -> etSearch.setText(""));

        findViewById(R.id.btn_sel_cancel).setOnClickListener(v -> exitSelection());
        findViewById(R.id.btn_sel_delete).setOnClickListener(v -> confirmDeleteSelected());
        findViewById(R.id.btn_sel_archive).setOnClickListener(v -> showArchiveDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    /** 读取全部日志，补齐值班人与目录名，再按当前搜索词过滤。 */
    private void load() {
        io.execute(() -> {
            List<LogFolder> folderList = db.logDao().getFolders();
            List<DutyLog> logs = db.logDao().getAll();

            Map<Long, String> folderNames = new HashMap<>();
            for (LogFolder f : folderList) folderNames.put(f.id, f.name);

            List<LogAdapter.Item> items = new ArrayList<>();
            for (DutyLog log : logs) {
                LogAdapter.Item item = new LogAdapter.Item();
                item.log = log;
                Person person = AutoSwitchRunner.resolveToday(this, LocalDate.ofEpochDay(log.epochDay));
                item.personName = person == null ? "" : person.name;
                item.folderName = log.folderId == 0 ? null : folderNames.get(log.folderId);
                items.add(item);
            }

            runOnUiThread(() -> {
                folders = folderList;
                allItems.clear();
                allItems.addAll(items);
                applyFilter();
            });
        });
    }

    /** 按搜索词过滤后刷新列表；空结果时切换提示文案。 */
    private void applyFilter() {
        String needle = query.trim().toLowerCase(Locale.getDefault());
        List<LogAdapter.Item> filtered = new ArrayList<>();
        for (LogAdapter.Item item : allItems) {
            if (needle.isEmpty() || matches(item, needle)) filtered.add(item);
        }
        adapter.submit(filtered);

        if (filtered.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText(needle.isEmpty()
                    ? "还没有日志\n点右上角铅笔记录，或长按桌面悬浮球快速记一笔"
                    : "没有找到匹配的日志");
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
    }

    /** 内容、值班人、日期（多种写法）都能命中。 */
    private boolean matches(LogAdapter.Item item, String needle) {
        return LogSearch.matches(item.log.content, item.personName, item.log.epochDay, needle);
    }

    // ===== 多选 =====

    /** 进入多选后刷新标题与底部操作栏。 */
    private void updateSelectionUi() {
        boolean selecting = adapter.isSelectionMode();
        selectionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        toolbar.setTitle(selecting ? "已选 " + adapter.selectedCount() + " 项" : "值班日志");
        if (selecting && adapter.selectedCount() == 0) {
            exitSelection();
        }
    }

    /** 退出多选模式。 */
    private void exitSelection() {
        adapter.setSelectionMode(false);
        selectionBar.setVisibility(View.GONE);
        toolbar.setTitle("值班日志");
    }

    /** 批量删除前确认。 */
    private void confirmDeleteSelected() {
        final List<Long> days = adapter.selectedDays();
        if (days.isEmpty()) return;

        ConfirmDialog.show(this, "删除日志",
                "确定删除选中的 " + days.size() + " 条日志吗？删除后无法恢复。",
                "删除", true,
                () -> io.execute(() -> {
                    db.logDao().deleteByDays(days);
                    runOnUiThread(() -> {
                        exitSelection();
                        load();
                    });
                }));
    }

    // ===== 归档 =====

    /** 选择归档目标目录，或现场新建一个。 */
    private void showArchiveDialog() {
        final List<Long> days = adapter.selectedDays();
        if (days.isEmpty()) return;

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_folder_pick, null);
        RadioGroup rg = view.findViewById(R.id.rg_folders);
        MaterialButton btnCancel = view.findViewById(R.id.btn_cancel);
        MaterialButton btnConfirm = view.findViewById(R.id.btn_confirm);
        View rowNew = view.findViewById(R.id.row_new_folder);

        final long[] chosen = {folders.isEmpty() ? -1 : folders.get(0).id};
        for (int i = 0; i < folders.size(); i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(i + 1);
            rb.setText(folders.get(i).name);
            rb.setTextSize(16);
            rb.setPadding(0, dp(12), 0, dp(12));
            if (i == 0) rb.setChecked(true);
            final long folderId = folders.get(i).id;
            rb.setOnClickListener(v -> chosen[0] = folderId);
            rg.addView(rb);
        }
        if (folders.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("还没有目录，点下面新建一个");
            hint.setTextSize(13);
            hint.setPadding(0, dp(8), 0, dp(8));
            rg.addView(hint);
        }

        AlertDialog dialog = new com.eafnv.callforward.ui.DutyDialogBuilder(this)
                .setTitle("归档到目录")
                .setView(view)
                .setPositiveButton(null, null)
                .setNegativeButton(null, null)
                .create();
        dialog.show();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        rowNew.setOnClickListener(v -> {
            dialog.dismiss();
            showNewFolderDialog(days);
        });
        btnConfirm.setOnClickListener(v -> {
            if (chosen[0] <= 0) {
                Toast.makeText(this, "请先新建一个目录", Toast.LENGTH_SHORT).show();
                return;
            }
            final long target = chosen[0];
            dialog.dismiss();
            io.execute(() -> {
                db.logDao().moveToFolder(days, target, System.currentTimeMillis());
                runOnUiThread(() -> {
                    Toast.makeText(this, "已归档 " + days.size() + " 条", Toast.LENGTH_SHORT).show();
                    exitSelection();
                    load();
                });
            });
        });
    }

    /** 新建目录后直接把选中的日志归档进去。 */
    private void showNewFolderDialog(final List<Long> days) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_input, null);
        final TextInputLayout til = view.findViewById(R.id.til_input);
        final TextInputEditText et = view.findViewById(R.id.et_input);
        MaterialButton btnCancel = view.findViewById(R.id.btn_cancel);
        MaterialButton btnConfirm = view.findViewById(R.id.btn_confirm);
        til.setHint("目录名称");

        AlertDialog dialog = new com.eafnv.callforward.ui.DutyDialogBuilder(this)
                .setTitle("新建目录")
                .setView(view)
                .setPositiveButton(null, null)
                .setNegativeButton(null, null)
                .create();
        dialog.show();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            String name = et.getText() == null ? "" : et.getText().toString().trim();
            if (TextUtils.isEmpty(name)) {
                til.setError("请输入目录名称");
                return;
            }
            dialog.dismiss();
            io.execute(() -> {
                LogFolder existing = db.logDao().findFolderByName(name);
                long folderId;
                if (existing != null) {
                    folderId = existing.id;
                } else {
                    LogFolder folder = new LogFolder();
                    folder.name = name;
                    folder.createdAt = System.currentTimeMillis();
                    folderId = db.logDao().insertFolder(folder);
                }
                db.logDao().moveToFolder(days, folderId, System.currentTimeMillis());
                runOnUiThread(() -> {
                    Toast.makeText(this, "已归档到「" + name + "」", Toast.LENGTH_SHORT).show();
                    exitSelection();
                    load();
                });
            });
        });
    }

    /** dp 转像素。 */
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
