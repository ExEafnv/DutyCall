package com.eafnv.callforward.ui;

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

import androidx.activity.OnBackPressedCallback;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 归档视图：按文件夹浏览日志。
 *
 * 顶层列出全部归档，长按可重命名或连同内容一起删除；
 * 进入某个归档后，长按单条日志可以删除、移出归档或更换归档位置。
 */
public class LogArchiveActivity extends AppCompatActivity {

    /** 当前模式：显示归档列表。 */
    private static final long MODE_FOLDERS = -1L;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private AppDatabase db;
    private MaterialToolbar toolbar;
    private RecyclerView recycler;
    private TextView tvEmpty;
    private View selectionBar;
    private EditText etSearch;
    private View btnClearSearch;

    private LogFolderAdapter folderAdapter;
    private LogAdapter logAdapter;

    /** 当前所在归档，MODE_FOLDERS 表示顶层。 */
    private long folderId = MODE_FOLDERS;
    private String folderTitle = "归档视图";

    private final List<LogFolderAdapter.Item> allFolders = new ArrayList<>();
    private final List<LogAdapter.Item> allLogs = new ArrayList<>();
    private String query = "";

    private final Runnable applyFilterTask = this::applyFilter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_archive);
        registerBackHandler();

        db = AppDatabase.get(this);

        toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> handleBack());

        recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        tvEmpty = findViewById(R.id.tv_empty);
        selectionBar = findViewById(R.id.selection_bar);
        etSearch = findViewById(R.id.et_search);
        btnClearSearch = findViewById(R.id.btn_clear_search);

        folderAdapter = new LogFolderAdapter();
        folderAdapter.setListener(new LogFolderAdapter.Listener() {
            @Override
            public void onClick(LogFolderAdapter.Item item) {
                if (folderAdapter.isSelectionMode()) {
                    folderAdapter.toggle(item.folder.id);
                    updateFolderSelectionUi();
                } else {
                    openFolder(item.folder);
                }
            }

            @Override
            public void onLongClick(LogFolderAdapter.Item item) {
                if (!folderAdapter.isSelectionMode()) {
                    folderAdapter.setSelectionMode(true);
                    folderAdapter.toggle(item.folder.id);
                    updateFolderSelectionUi();
                }
            }
        });

        logAdapter = new LogAdapter();
        logAdapter.setListener(new LogAdapter.Listener() {
            @Override
            public void onClick(LogAdapter.Item item) {
                startActivity(LogEditActivity.intent(LogArchiveActivity.this, item.log.epochDay));
            }

            @Override
            public void onLongClick(LogAdapter.Item item) {
                showLogActions(item);
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

        findViewById(R.id.btn_sel_cancel).setOnClickListener(v -> exitFolderSelection());
        findViewById(R.id.btn_sel_rename).setOnClickListener(v -> renameSelected());
        findViewById(R.id.btn_sel_delete).setOnClickListener(v -> deleteSelectedFolders());

        showFolders();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (folderId == MODE_FOLDERS) {
            showFolders();
        } else {
            showLogs(folderId);
        }
    }

    /**
     * 返回：归档内部先退回顶层，顶层再退出。
     *
     * 必须走 OnBackPressedDispatcher：targetSdk 36 起系统默认开启预测性返回，
     * 返回手势不再调用 onBackPressed()，覆写它会导致手势直接退出整个页面。
     */
    private void registerBackHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (handleBack()) return;
                setEnabled(false);                              // 顶层：交回系统退出
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }

    /** @return true 表示内部已消费这次返回。 */
    private boolean handleBack() {
        if (folderAdapter.isSelectionMode()) {
            exitFolderSelection();
            return true;
        }
        if (folderId != MODE_FOLDERS) {
            showFolders();
            return true;
        }
        return false;
    }

    // ===== 数据加载 =====

    /** 切到顶层：列出全部归档。 */
    private void showFolders() {
        folderId = MODE_FOLDERS;
        folderTitle = "归档视图";
        toolbar.setTitle(folderTitle);
        recycler.setAdapter(folderAdapter);
        exitFolderSelection();
        etSearch.setText("");
        etSearch.setHint("搜索归档名称");

        io.execute(() -> {
            List<LogFolder> folders = db.logDao().getFolders();
            List<LogFolderAdapter.Item> items = new ArrayList<>();
            for (LogFolder folder : folders) {
                LogFolderAdapter.Item item = new LogFolderAdapter.Item();
                item.folder = folder;
                item.count = db.logDao().countLogsInFolder(folder.id);
                Long latest = db.logDao().latestDayInFolder(folder.id);
                item.latestDay = latest == null ? 0 : latest;
                items.add(item);
            }
            runOnUiThread(() -> {
                allFolders.clear();
                allFolders.addAll(items);
                applyFilter();
            });
        });
    }

    /** 进入某个归档：列出里面的日志。 */
    private void openFolder(LogFolder folder) {
        folderId = folder.id;
        folderTitle = folder.name;
        showLogs(folder.id);
    }

    /** 加载指定归档里的日志。 */
    private void showLogs(long id) {
        toolbar.setTitle(folderTitle);
        recycler.setAdapter(logAdapter);
        exitFolderSelection();
        etSearch.setText("");
        etSearch.setHint("搜索这个归档里的日志");

        io.execute(() -> {
            List<DutyLog> logs = db.logDao().getByFolder(id);
            List<LogAdapter.Item> items = new ArrayList<>();
            for (DutyLog log : logs) {
                LogAdapter.Item item = new LogAdapter.Item();
                item.log = log;
                Person person = AutoSwitchRunner.resolveToday(this, LocalDate.ofEpochDay(log.epochDay));
                item.personName = person == null ? "" : person.name;
                items.add(item);
            }
            runOnUiThread(() -> {
                allLogs.clear();
                allLogs.addAll(items);
                applyFilter();
            });
        });
    }

    /** 按当前模式与搜索词刷新列表。 */
    private void applyFilter() {
        String needle = query.trim().toLowerCase(Locale.getDefault());

        if (folderId == MODE_FOLDERS) {
            List<LogFolderAdapter.Item> filtered = new ArrayList<>();
            for (LogFolderAdapter.Item item : allFolders) {
                String name = item.folder.name == null ? "" : item.folder.name.toLowerCase(Locale.getDefault());
                if (needle.isEmpty() || name.contains(needle)) filtered.add(item);
            }
            folderAdapter.submit(filtered);
            updateEmpty(filtered.isEmpty(), needle.isEmpty()
                    ? "还没有归档\n新建日志会自动归档到「2026年10月」这样的月份目录"
                    : "没有找到匹配的归档");
        } else {
            List<LogAdapter.Item> filtered = new ArrayList<>();
            for (LogAdapter.Item item : allLogs) {
                if (needle.isEmpty()
                        || LogSearch.matches(item.log.content, item.personName, item.log.epochDay, needle)) {
                    filtered.add(item);
                }
            }
            logAdapter.submit(filtered);
            updateEmpty(filtered.isEmpty(), needle.isEmpty()
                    ? "这个归档里还没有日志"
                    : "没有找到匹配的日志");
        }
    }

    /** 空状态文案与显隐。 */
    private void updateEmpty(boolean empty, String text) {
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        tvEmpty.setText(text);
    }

    // ===== 归档的多选操作 =====

    private void updateFolderSelectionUi() {
        boolean selecting = folderAdapter.isSelectionMode();
        selectionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        toolbar.setTitle(selecting ? "已选 " + folderAdapter.selectedCount() + " 项" : folderTitle);
        if (selecting && folderAdapter.selectedCount() == 0) {
            exitFolderSelection();
        }
    }

    private void exitFolderSelection() {
        folderAdapter.setSelectionMode(false);
        selectionBar.setVisibility(View.GONE);
        toolbar.setTitle(folderTitle);
    }

    /** 重命名选中的归档（仅单条）。 */
    private void renameSelected() {
        final LogFolderAdapter.Item item = folderAdapter.singleSelected();
        if (item == null) {
            Toast.makeText(this, "请选择并且只选择一个归档", Toast.LENGTH_SHORT).show();
            return;
        }

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_input, null);
        final TextInputLayout til = view.findViewById(R.id.til_input);
        final TextInputEditText et = view.findViewById(R.id.et_input);
        MaterialButton btnCancel = view.findViewById(R.id.btn_cancel);
        MaterialButton btnConfirm = view.findViewById(R.id.btn_confirm);
        til.setHint("归档名称");
        et.setText(item.folder.name);
        et.setSelection(et.getText().length());

        AlertDialog dialog = new com.eafnv.callforward.ui.DutyDialogBuilder(this)
                .setTitle("重命名归档")
                .setView(view)
                .setPositiveButton(null, null)
                .setNegativeButton(null, null)
                .create();
        dialog.show();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            String name = et.getText() == null ? "" : et.getText().toString().trim();
            if (TextUtils.isEmpty(name)) {
                til.setError("请输入归档名称");
                return;
            }
            dialog.dismiss();
            io.execute(() -> {
                LogFolder same = db.logDao().findFolderByName(name);
                if (same != null && same.id != item.folder.id) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "已存在同名归档", Toast.LENGTH_SHORT).show());
                    return;
                }
                db.logDao().renameFolder(item.folder.id, name);
                runOnUiThread(() -> {
                    exitFolderSelection();
                    showFolders();
                });
            });
        });
    }

    /** 删除选中的归档，连同里面的日志。 */
    private void deleteSelectedFolders() {
        final List<Long> ids = folderAdapter.selectedIds();
        if (ids.isEmpty()) return;

        int logCount = 0;
        for (LogFolderAdapter.Item item : allFolders) {
            if (ids.contains(item.folder.id)) logCount += item.count;
        }
        final int totalLogs = logCount;

        ConfirmDialog.show(this, "删除归档",
                "将删除 " + ids.size() + " 个归档，以及其中的 " + totalLogs + " 条日志。删除后无法恢复。",
                "删除", true,
                () -> io.execute(() -> {
                    for (long id : ids) {
                        db.logDao().deleteLogsInFolder(id);
                        db.logDao().deleteFolder(id);
                    }
                    runOnUiThread(() -> {
                        exitFolderSelection();
                        showFolders();
                    });
                }));
    }
    // ===== 归档内日志的单条操作 =====

    /** 长按某条日志：删除 / 移出归档 / 更换归档位置。 */
    private void showLogActions(final LogAdapter.Item item) {
        String[] actions = {"删除日志", "移出归档", "更换归档位置"};
        new com.eafnv.callforward.ui.DutyDialogBuilder(this)
                .setTitle(LogSearch.describeDay(item.log.epochDay))
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        confirmDeleteLog(item);
                    } else if (which == 1) {
                        io.execute(() -> {
                            db.logDao().unfileDay(item.log.epochDay, System.currentTimeMillis());
                            runOnUiThread(() -> {
                                Toast.makeText(this, "已移出归档", Toast.LENGTH_SHORT).show();
                                showLogs(folderId);
                            });
                        });
                    } else {
                        pickFolderFor(item);
                    }
                })
                .show();
    }

    /** 删除单条日志前确认。 */
    private void confirmDeleteLog(final LogAdapter.Item item) {
        ConfirmDialog.show(this, "删除日志",
                "确定删除这天的日志吗？删除后无法恢复。",
                "删除", true,
                () -> io.execute(() -> {
                    db.logDao().deleteByDay(item.log.epochDay);
                    runOnUiThread(() -> showLogs(folderId));
                }));
    }

    /** 选择另一个归档（或新建一个）来存放这条日志。 */
    private void pickFolderFor(final LogAdapter.Item item) {
        io.execute(() -> {
            List<LogFolder> folders = db.logDao().getFolders();
            runOnUiThread(() -> {
                View view = LayoutInflater.from(this).inflate(R.layout.dialog_folder_pick, null);
                RadioGroup rg = view.findViewById(R.id.rg_folders);
                MaterialButton btnCancel = view.findViewById(R.id.btn_cancel);
                MaterialButton btnConfirm = view.findViewById(R.id.btn_confirm);
                View rowNew = view.findViewById(R.id.row_new_folder);

                final long[] chosen = {-1};
                int index = 1;
                for (LogFolder folder : folders) {
                    if (folder.id == folderId) continue;
                    RadioButton rb = new RadioButton(this);
                    rb.setId(index++);
                    rb.setText(folder.name);
                    rb.setTextSize(16);
                    rb.setPadding(0, dp(12), 0, dp(12));
                    final long target = folder.id;
                    rb.setOnClickListener(v -> chosen[0] = target);
                    rg.addView(rb);
                }
                if (rg.getChildCount() == 0) {
                    TextView hint = new TextView(this);
                    hint.setText("没有其它归档，点下面新建一个");
                    hint.setTextSize(13);
                    hint.setPadding(0, dp(8), 0, dp(8));
                    rg.addView(hint);
                }

                AlertDialog dialog = new com.eafnv.callforward.ui.DutyDialogBuilder(this)
                        .setTitle("更换归档位置")
                        .setView(view)
                        .setPositiveButton(null, null)
                        .setNegativeButton(null, null)
                        .create();
                dialog.show();

                btnCancel.setOnClickListener(v -> dialog.dismiss());
                rowNew.setOnClickListener(v -> {
                    dialog.dismiss();
                    askNewFolder(item);
                });
                btnConfirm.setOnClickListener(v -> {
                    if (chosen[0] <= 0) {
                        Toast.makeText(this, "请选择一个归档", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final long target = chosen[0];
                    dialog.dismiss();
                    moveLog(item, target);
                });
            });
        });
    }

    /** 新建归档并把日志移过去。 */
    private void askNewFolder(final LogAdapter.Item item) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_input, null);
        final TextInputLayout til = view.findViewById(R.id.til_input);
        final TextInputEditText et = view.findViewById(R.id.et_input);
        MaterialButton btnCancel = view.findViewById(R.id.btn_cancel);
        MaterialButton btnConfirm = view.findViewById(R.id.btn_confirm);
        til.setHint("归档名称");

        AlertDialog dialog = new com.eafnv.callforward.ui.DutyDialogBuilder(this)
                .setTitle("新建归档")
                .setView(view)
                .setPositiveButton(null, null)
                .setNegativeButton(null, null)
                .create();
        dialog.show();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            String name = et.getText() == null ? "" : et.getText().toString().trim();
            if (TextUtils.isEmpty(name)) {
                til.setError("请输入归档名称");
                return;
            }
            dialog.dismiss();
            io.execute(() -> {
                LogFolder existing = db.logDao().findFolderByName(name);
                long target;
                if (existing != null) {
                    target = existing.id;
                } else {
                    LogFolder folder = new LogFolder();
                    folder.name = name;
                    folder.createdAt = System.currentTimeMillis();
                    target = db.logDao().insertFolder(folder);
                }
                moveLog(item, target);
            });
        });
    }

    /** 把日志移动到指定归档并刷新。 */
    private void moveLog(final LogAdapter.Item item, final long target) {
        io.execute(() -> {
            List<Long> days = new ArrayList<>();
            days.add(item.log.epochDay);
            db.logDao().moveToFolder(days, target, System.currentTimeMillis());
            runOnUiThread(() -> {
                Toast.makeText(this, "已更换归档", Toast.LENGTH_SHORT).show();
                showLogs(folderId);
            });
        });
    }

    /** dp 转像素。 */
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
