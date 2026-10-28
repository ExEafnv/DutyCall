package com.eafnv.callforward.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.eafnv.callforward.R;
import com.eafnv.callforward.data.AppDatabase;
import com.eafnv.callforward.data.DutyLog;
import com.eafnv.callforward.data.LogFolder;
import com.eafnv.callforward.data.Person;
import com.eafnv.callforward.mmi.AutoSwitchRunner;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 单日日志编辑页。
 *
 * 三个入口共用：日历点某天、今日卡片右上角铅笔、长按悬浮球。
 * 一天只保留一条日志，再次保存即覆盖。
 */
public class LogEditActivity extends AppCompatActivity {

    /** 要编辑的日期，用 epochDay 传；不传则默认今天。 */
    public static final String EXTRA_DAY = "extra_day";

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("M月d日");

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private AppDatabase db;
    private long epochDay;
    private DutyLog existing;

    private TextView tvDate;
    private TextView tvPerson;
    private TextInputEditText etContent;
    private View btnDelete;

    /** 读取目标日期、当天值班人与已有日志。 */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_edit);

        db = AppDatabase.get(this);
        epochDay = getIntent().getLongExtra(EXTRA_DAY, LocalDate.now().toEpochDay());

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvDate = findViewById(R.id.tv_log_date);
        tvPerson = findViewById(R.id.tv_log_person);
        etContent = findViewById(R.id.et_log_content);
        btnDelete = findViewById(R.id.btn_log_delete);

        findViewById(R.id.btn_log_cancel).setOnClickListener(v -> finish());
        findViewById(R.id.btn_log_save).setOnClickListener(v -> save());
        btnDelete.setOnClickListener(v -> confirmDelete());

        load();
    }

    /** 加载日志内容与当天值班人。 */
    private void load() {
        io.execute(() -> {
            DutyLog log = db.logDao().getByDay(epochDay);
            LocalDate date = LocalDate.ofEpochDay(epochDay);
            Person person = AutoSwitchRunner.resolveToday(this, date);
            runOnUiThread(() -> {
                existing = log;
                tvDate.setText(date.format(DATE_FMT) + " " + weekdayOf(date));
                tvPerson.setText(person == null ? "当天没有排班人员" : "当天值班：" + person.name);
                if (log != null && !TextUtils.isEmpty(log.content)) {
                    etContent.setText(log.content);
                    etContent.setSelection(etContent.getText().length());
                }
                btnDelete.setVisibility(log != null ? View.VISIBLE : View.GONE);
            });
        });
    }

    /** 保存日志；内容为空时提示并阻止保存。 */
    private void save() {
        String content = etContent.getText() == null ? "" : etContent.getText().toString().trim();
        if (TextUtils.isEmpty(content)) {
            Toast.makeText(this, "日志内容不能为空", Toast.LENGTH_SHORT).show();
            return;
        }

        long now = System.currentTimeMillis();
        io.execute(() -> {
            if (existing == null) {
                DutyLog log = new DutyLog();
                log.epochDay = epochDay;
                log.content = content;
                log.folderId = ensureMonthFolder(db, epochDay, now);
                log.createdAt = now;
                log.updatedAt = now;
                db.logDao().insert(log);
            } else {
                db.logDao().updateByDay(epochDay, content, now);
            }
            runOnUiThread(() -> {
                Toast.makeText(this, "日志已保存", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            });
        });
    }

    /** 删除日志前确认一次。 */
    private void confirmDelete() {
        ConfirmDialog.show(this, "删除日志",
                "确定删除这一天的日志吗？删除后无法恢复。",
                "删除", true,
                () -> io.execute(() -> {
                    db.logDao().deleteByDay(epochDay);
                    runOnUiThread(() -> {
                        setResult(RESULT_OK);
                        finish();
                    });
                }));
    }

    /** 取星期几的中文写法。 */
    private static String weekdayOf(LocalDate date) {
        DayOfWeek d = date.getDayOfWeek();
        String[] names = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
        return names[d.getValue() - 1];
    }

    /**
     * 新日志自动归到当月的目录里（如「2026年10月」），列表上就会带上这个标签。
     *
     * 目录不存在时创建；已存在时直接复用。手动归档过的日志不会被这里改写。
     */
    private static long ensureMonthFolder(AppDatabase db, long epochDay, long now) {
        LocalDate date = LocalDate.ofEpochDay(epochDay);
        String name = date.getYear() + "年" + date.getMonthValue() + "月";

        LogFolder existing = db.logDao().findFolderByName(name);
        if (existing != null) return existing.id;

        LogFolder folder = new LogFolder();
        folder.name = name;
        folder.createdAt = now;
        return db.logDao().insertFolder(folder);
    }

    /** 供外部按日期打开编辑页。 */
    public static Intent intent(Context context, long day) {
        return new Intent(context, LogEditActivity.class).putExtra(EXTRA_DAY, day);
    }
}
