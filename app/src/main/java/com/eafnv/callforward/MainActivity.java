package com.eafnv.callforward;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eafnv.callforward.data.AppDatabase;
import com.eafnv.callforward.data.DayOverride;
import com.eafnv.callforward.data.Person;
import com.eafnv.callforward.data.ScheduleEngine;
import com.eafnv.callforward.data.ScheduleVersion;
import com.eafnv.callforward.ui.MonthCalendarAdapter;
import com.eafnv.callforward.ui.PeopleActivity;
import com.eafnv.callforward.ui.ScheduleActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_CALL_PHONE = 1001;
    private static final String PREFS = "duty_prefs";
    private static final String KEY_LAST_IS_SET = "last_is_set";
    private static final String KEY_LAST_PERSON = "last_person"; // 存排班人名，或手动号码
    private static final String KEY_LAST_PHONE = "last_phone";   // 存实际号码
    private static final String KEY_IS_MANUAL = "is_manual";     // 标记当前是否为手动输入
    private static final String KEY_LAST_TIME = "last_time";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEEE · M月d日");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("M月d日 HH:mm");

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private AppDatabase db;
    private SharedPreferences prefs;

    // 排班偏移量
    private int offsetM = 0, offsetW = 0, offsetD = 0;

    private TextView tvTodayPerson, tvTodayDate, tvTodayPhone;
    private TextView tvStateValue, tvStateTime;
    private MaterialButton btnAction;
    private TextView tvCalendarTitle;

    private MonthCalendarAdapter calendarAdapter;
    private LocalDate displayMonth = LocalDate.now().withDayOfMonth(1);

    private Person todayPerson;
    private String pendingCode = null;
    private Runnable pendingOnSuccess = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        boolean isDarkMode = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        androidx.core.view.WindowInsetsControllerCompat controller = androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(!isDarkMode);
            controller.setAppearanceLightNavigationBars(!isDarkMode);
        }

        db = AppDatabase.get(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        // 读取偏移量
        offsetM = prefs.getInt("offset_m", 0);
        offsetW = prefs.getInt("offset_w", 0);
        offsetD = prefs.getInt("offset_d", 0);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        tvTodayPerson = findViewById(R.id.tv_today_person);
        tvTodayDate = findViewById(R.id.tv_today_date);
        tvTodayPhone = findViewById(R.id.tv_today_phone);
        tvStateValue = findViewById(R.id.tv_state_value);
        tvStateTime = findViewById(R.id.tv_state_time);
        btnAction = findViewById(R.id.btn_action);
        tvCalendarTitle = findViewById(R.id.tv_calendar_title);

        findViewById(R.id.btn_people_manage).setOnClickListener(v ->
                startActivity(new Intent(this, PeopleActivity.class)));
        findViewById(R.id.btn_schedule_manage).setOnClickListener(v ->
                startActivity(new Intent(this, ScheduleActivity.class)));

        // 齿轮按钮：弹出微调对话框
        findViewById(R.id.btn_fine_tune).setOnClickListener(v -> showFineTuneDialog());

        // 点击状态文字或提示文字，弹出手动设置窗口
        tvStateValue.setOnClickListener(v -> showManualTransferDialog());
        tvStateTime.setOnClickListener(v -> showManualTransferDialog());

        RecyclerView recyclerCalendar = findViewById(R.id.recycler_calendar);
        recyclerCalendar.setLayoutManager(new GridLayoutManager(this, 7));
        calendarAdapter = new MonthCalendarAdapter();
        recyclerCalendar.setAdapter(calendarAdapter);
        calendarAdapter.setOnDayClick(this::onCalendarDayClick);

        findViewById(R.id.btn_prev_month).setOnClickListener(v -> {
            displayMonth = displayMonth.minusMonths(1);
            refreshCalendarView();
        });
        findViewById(R.id.btn_next_month).setOnClickListener(v -> {
            displayMonth = displayMonth.plusMonths(1);
            refreshCalendarView();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
        renderState();
    }

    private void loadData() {
        io.execute(() -> {
            LocalDate today = LocalDate.now();
            long day = today.toEpochDay();
            List<Person> people = db.personDao().getAll();
            ScheduleVersion todayVersion = db.scheduleDao().findVersionForDay(day);
            DayOverride todayOverride = db.scheduleDao().findOverride(day);
            Person p = ScheduleEngine.resolve(people, todayVersion, todayOverride, today, offsetM, offsetW, offsetD);

            List<MonthCalendarAdapter.DayItem> calendarItems = buildCalendarData(displayMonth, people);

            runOnUiThread(() -> {
                todayPerson = p;
                tvTodayDate.setText(today.format(DATE_FMT) + (todayOverride != null ? " · 临时换班" : ""));
                if (p == null) {
                    tvTodayPerson.setText("今日暂未安排");
                    tvTodayPhone.setVisibility(TextView.GONE);
                } else {
                    tvTodayPerson.setText(p.name);
                    tvTodayPhone.setText(p.phoneNumber);
                    tvTodayPhone.setVisibility(TextView.VISIBLE);
                }

                calendarAdapter.submit(calendarItems);
                updateActionButton();
            });
        });
    }

    private void refreshCalendarView() {
        io.execute(() -> {
            List<Person> people = db.personDao().getAll();
            List<MonthCalendarAdapter.DayItem> calendarItems = buildCalendarData(displayMonth, people);
            runOnUiThread(() -> calendarAdapter.submit(calendarItems));
        });
    }

    private List<MonthCalendarAdapter.DayItem> buildCalendarData(LocalDate targetMonth, List<Person> people) {
        List<MonthCalendarAdapter.DayItem> items = new ArrayList<>();
        LocalDate firstOfMonth = targetMonth.withDayOfMonth(1);
        LocalDate lastOfMonth = targetMonth.withDayOfMonth(targetMonth.lengthOfMonth());
        LocalDate today = LocalDate.now();

        tvCalendarTitle.setText(targetMonth.getYear() + "年" + targetMonth.getMonthValue() + "月");

        int startDayValue = firstOfMonth.getDayOfWeek().getValue();
        for (int i = 1; i < startDayValue; i++) {
            items.add(new MonthCalendarAdapter.DayItem());
        }

        for (LocalDate d = firstOfMonth; !d.isAfter(lastOfMonth); d = d.plusDays(1)) {
            MonthCalendarAdapter.DayItem item = new MonthCalendarAdapter.DayItem();
            item.date = d;
            item.isToday = d.equals(today);

            ScheduleVersion v = db.scheduleDao().findVersionForDay(d.toEpochDay());
            DayOverride override = db.scheduleDao().findOverride(d.toEpochDay());
            item.person = ScheduleEngine.resolve(people, v, override, d, offsetM, offsetW, offsetD);

            items.add(item);
        }
        return items;
    }

    private void onCalendarDayClick(MonthCalendarAdapter.DayItem item) {
        io.execute(() -> {
            List<Person> people = db.personDao().getAll();
            runOnUiThread(() -> showOverrideDialog(item, people));
        });
    }

    private void showOverrideDialog(MonthCalendarAdapter.DayItem item, List<Person> people) {
        if (people.isEmpty()) {
            Toast.makeText(this, "请先添加人员", Toast.LENGTH_SHORT).show();
            return;
        }

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_single_choice, null);
        android.widget.RadioGroup rg = view.findViewById(R.id.rg_choices);
        MaterialButton btnCancel = view.findViewById(R.id.btn_cancel);
        MaterialButton btnConfirm = view.findViewById(R.id.btn_confirm);

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
                if (item.person != null && people.get(i - 1).id == item.person.id) {
                    checkedId = i;
                }
            }
            rg.addView(rb);
        }
        if (checkedId != -1) rg.check(checkedId);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(item.date + " · 临时换人")
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
                io.execute(() -> {
                    if (selectedId == 0) {
                        db.scheduleDao().deleteOverride(item.date.toEpochDay());
                    } else {
                        DayOverride o = new DayOverride();
                        o.epochDay = item.date.toEpochDay();
                        o.personId = people.get(selectedId - 1).id;
                        db.scheduleDao().upsertOverride(o);
                    }
                    runOnUiThread(this::loadData);
                });
            }
        });
    }

    // 弹出手动输入号码的对话框
    private void showManualTransferDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_manual_transfer, null);
        EditText etNumber = view.findViewById(R.id.et_manual_number);
        MaterialButton btnCancel = view.findViewById(R.id.btn_cancel);
        MaterialButton btnConfirm = view.findViewById(R.id.btn_confirm);

        // 如果之前是手动设置的，回显号码
        if (prefs.getBoolean(KEY_IS_MANUAL, false)) {
            etNumber.setText(prefs.getString(KEY_LAST_PHONE, ""));
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("手动设置转移")
                .setView(view)
                .setPositiveButton(null, null)
                .setNegativeButton(null, null)
                .create();
        dialog.show();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            String number = etNumber.getText().toString().trim();
            if (TextUtils.isEmpty(number)) {
                Toast.makeText(this, "号码不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            // 号码校验
            if (!number.matches("^1[3-9]\\d{9}$")) {
                Toast.makeText(this, "请输入正确的 11 位手机号码", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            // 执行手动转移
            executeManualTransfer(number);
        });
    }

    private void executeManualTransfer(String number) {
        String code = "**21*" + number + "#";
        dialMmi(code, () -> {
            prefs.edit()
                    .putBoolean(KEY_LAST_IS_SET, true)
                    .putBoolean(KEY_IS_MANUAL, true)
                    .putString(KEY_LAST_PERSON, "手动号码")
                    .putString(KEY_LAST_PHONE, number)
                    .putLong(KEY_LAST_TIME, System.currentTimeMillis())
                    .apply();
            renderState();
        });
    }

    private void renderState() {
        boolean isSet = prefs.getBoolean(KEY_LAST_IS_SET, false);
        boolean isManual = prefs.getBoolean(KEY_IS_MANUAL, false);
        String name = prefs.getString(KEY_LAST_PERSON, "");
        String phone = prefs.getString(KEY_LAST_PHONE, "");
        long time = prefs.getLong(KEY_LAST_TIME, 0);

        if (isSet) {
            if (isManual) {
                tvStateValue.setText("已转移至手动号码 " + phone);
            } else {
                tvStateValue.setText("已转移至 " + name);
            }
            if (time > 0) {
                // 已设置时，小字显示操作时间，并暗示可修改
                tvStateTime.setText("上次操作：" + formatTime(time) + " · 点击此处可修改");
                tvStateTime.setVisibility(TextView.VISIBLE);
            }
        } else {
            tvStateValue.setText("未设置转移");
            // 未设置时，小字作为引导提示
            tvStateTime.setText("点击此处可手动指定号码");
            tvStateTime.setVisibility(TextView.VISIBLE);
        }
        updateActionButton();
    }

    private void updateActionButton() {
        boolean isSet = prefs.getBoolean(KEY_LAST_IS_SET, false);
        boolean isManual = prefs.getBoolean(KEY_IS_MANUAL, false);
        String lastPhone = prefs.getString(KEY_LAST_PHONE, "");

        if (isSet) {
            // 如果已经设置了转移
            if (todayPerson != null && !isManual && lastPhone.equals(todayPerson.phoneNumber)) {
                // 场景A：转移给了今日排班人，按钮变“取消”
                btnAction.setText("取消转移");
                btnAction.setOnClickListener(v -> onCancelClick());
            } else if (todayPerson != null) {
                // 场景B：有排班，但当前转移给了别人（比如昨天的人，或者手动号码），按钮变“切换”
                btnAction.setText("一键切换至今日值班");
                btnAction.setOnClickListener(v -> onSwitchClick());
            } else {
                // 场景C：今天没排班，但设置了转移（必然是通过手动设置的），按钮必须能“取消”
                btnAction.setText("取消转移");
                btnAction.setOnClickListener(v -> onCancelClick());
            }
            return; // 设置过转移的情况处理完了，直接结束
        }

        // 如果没设置转移
        if (todayPerson == null) {
            // 场景D：没设置转移，且今天没排班
            btnAction.setText("前往排班管理");
            btnAction.setOnClickListener(v -> startActivity(new Intent(this, ScheduleActivity.class)));
        } else {
            // 场景E：没设置转移，且今天有排班
            btnAction.setText("一键切换至今日值班");
            btnAction.setOnClickListener(v -> onSwitchClick());
        }
    }

    private String formatTime(long epochMillis) {
        LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
        return dt.format(TIME_FMT);
    }

    private void onSwitchClick() {
        if (todayPerson == null) return;
        String code = "**21*" + todayPerson.phoneNumber + "#";
        final String name = todayPerson.name;
        final String phone = todayPerson.phoneNumber;
        dialMmi(code, () -> {
            prefs.edit()
                    .putBoolean(KEY_LAST_IS_SET, true)
                    .putBoolean(KEY_IS_MANUAL, false)
                    .putString(KEY_LAST_PERSON, name)
                    .putString(KEY_LAST_PHONE, phone)
                    .putLong(KEY_LAST_TIME, System.currentTimeMillis())
                    .apply();
            renderState();
        });
    }

    private void onCancelClick() {
        dialMmi("##21#", () -> {
            prefs.edit()
                    .putBoolean(KEY_LAST_IS_SET, false)
                    .putBoolean(KEY_IS_MANUAL, false)
                    .putString(KEY_LAST_PERSON, "")
                    .putString(KEY_LAST_PHONE, "")
                    .putLong(KEY_LAST_TIME, System.currentTimeMillis())
                    .apply();
            renderState();
        });
    }

    private void dialMmi(String code, Runnable onSuccess) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingCode = code;
            pendingOnSuccess = onSuccess;
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CALL_PHONE}, REQ_CALL_PHONE);
            return;
        }
        doCall(code);
        if (onSuccess != null) onSuccess.run();
    }

    private void doCall(String code) {
        try {
            String dialString = code.replace("#", "%23");
            Intent intent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + dialString));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "拨号失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CALL_PHONE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingCode != null) {
                    doCall(pendingCode);
                    if (pendingOnSuccess != null) pendingOnSuccess.run();
                    pendingCode = null;
                    pendingOnSuccess = null;
                }
            } else {
                Toast.makeText(this, "需要拨号权限才能执行转移", Toast.LENGTH_LONG).show();
            }
        }
    }

    // =====================================================================
    // 排班进度微调对话框
    // =====================================================================
    private void showFineTuneDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_fine_tune, null);
        TextView tvM = view.findViewById(R.id.tv_m_value);
        TextView tvW = view.findViewById(R.id.tv_w_value);
        TextView tvD = view.findViewById(R.id.tv_d_value);

        // 临时变量，用于点击保存前不修改全局
        final int[] tempM = {offsetM};
        final int[] tempW = {offsetW};
        final int[] tempD = {offsetD};

        tvM.setText(String.valueOf(tempM[0]));
        tvW.setText(String.valueOf(tempW[0]));
        tvD.setText(String.valueOf(tempD[0]));

        view.findViewById(R.id.btn_m_minus).setOnClickListener(v -> { tempM[0]--; tvM.setText(String.valueOf(tempM[0])); });
        view.findViewById(R.id.btn_m_plus).setOnClickListener(v -> { tempM[0]++; tvM.setText(String.valueOf(tempM[0])); });
        view.findViewById(R.id.btn_w_minus).setOnClickListener(v -> { tempW[0]--; tvW.setText(String.valueOf(tempW[0])); });
        view.findViewById(R.id.btn_w_plus).setOnClickListener(v -> { tempW[0]++; tvW.setText(String.valueOf(tempW[0])); });
        view.findViewById(R.id.btn_d_minus).setOnClickListener(v -> { tempD[0]--; tvD.setText(String.valueOf(tempD[0])); });
        view.findViewById(R.id.btn_d_plus).setOnClickListener(v -> { tempD[0]++; tvD.setText(String.valueOf(tempD[0])); });

        MaterialButton btnCancel = view.findViewById(R.id.btn_cancel);
        MaterialButton btnConfirm = view.findViewById(R.id.btn_confirm);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("排班进度微调")
                .setView(view)
                .setPositiveButton(null, null)
                .setNegativeButton(null, null)
                .create();
        dialog.show();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            offsetM = tempM[0];
            offsetW = tempW[0];
            offsetD = tempD[0];
            prefs.edit()
                    .putInt("offset_m", offsetM)
                    .putInt("offset_w", offsetW)
                    .putInt("offset_d", offsetD)
                    .apply();
            dialog.dismiss();
            loadData(); // 重新加载，日历立刻刷新
        });
    }
}