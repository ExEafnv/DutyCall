package com.eafnv.callforward;

import android.Manifest;
import android.animation.Animator;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.eafnv.callforward.data.DutyLog;
import com.eafnv.callforward.data.Person;
import com.eafnv.callforward.data.ScheduleEngine;
import com.eafnv.callforward.data.ScheduleVersion;
import com.eafnv.callforward.mmi.AutoSwitchRunner;
import com.eafnv.callforward.mmi.MmiExecutor;
import com.eafnv.callforward.ui.MonthCalendarAdapter;
import com.eafnv.callforward.ui.LogActivity;
import com.eafnv.callforward.ui.LogEditActivity;
import com.eafnv.callforward.ui.PeopleActivity;
import com.eafnv.callforward.ui.ScheduleActivity;
import com.eafnv.callforward.ui.SettingsActivity;
import com.eafnv.callforward.util.AppPrefs;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首页：展示今日值班人、转移状态与月历，所有前台转移操作都从这里发起。
 *
 * 界面只负责触发和展示，真正的 USSD 交互由 MmiExecutor 与 MmiService 完成。
 */
public class MainActivity extends AppCompatActivity {

    /** 拨号权限的请求码。 */
    private static final int REQ_CALL_PHONE = 1001;
    /** 首页状态用的 SharedPreferences 文件名。 */
    private static final String PREFS = "duty_prefs";
    /** 上次操作是否成功设置了转移。 */
    private static final String KEY_LAST_IS_SET = "last_is_set";
    /** 上次转移的目标：排班人名或手动号码。 */
    private static final String KEY_LAST_PERSON = "last_person";
    /** 上次实际下发的号码。 */
    private static final String KEY_LAST_PHONE = "last_phone";
    /** 当前状态是否来自手动输入。 */
    private static final String KEY_IS_MANUAL = "is_manual";
    /** 上次操作的时间戳，毫秒。 */
    private static final String KEY_LAST_TIME = "last_time";

    /** 首页日期格式，例如“星期一 · 10月27日”。 */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEEE · M月d日");
    /** 状态区时间格式，例如“10月27日 09:30”。 */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("M月d日 HH:mm");

    /** 单线程池：数据库读写与状态刷新都串行丢到这里。 */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private AppDatabase db;
    private SharedPreferences prefs;

    /** 排班偏移量：月、周、日三个维度，设置页可调。 */
    private int offsetM = 0, offsetW = 0, offsetD = 0;

    /** 今日值班卡片的姓名、日期、号码三个文本。 */
    private TextView tvTodayPerson, tvTodayDate, tvTodayPhone;
    /** 转移状态的两行文字，点击可手动指定号码。 */
    private TextView tvStateValue, tvStateTime;
    /** 底部主操作按钮，文案与点击行为随状态变化。 */
    private MaterialButton btnAction;
    /** 日历卡片标题，显示当前查看的年月。 */
    private TextView tvCalendarTitle;

    private MonthCalendarAdapter calendarAdapter;
    private RecyclerView recyclerCalendar;
    /** 是否已经挂过卡片布局监听，避免重复挂。 */
    private boolean calendarHeightHooked;
    /** 当前查看的月份，始终是 1 号，避免月末加减时溢出。 */
    private LocalDate displayMonth = LocalDate.now().withDayOfMonth(1);

    /** 今日值班人，没有排班时为 null。 */
    private Person todayPerson;
    /** 等权限授予后再执行的动作。 */
    private Runnable pendingAction = null;
    /** 监听后台执行结果的状态广播。 */
    private BroadcastReceiver stateReceiver;

    /** 初始化界面、数据库与月历，并在自动转移开启时拉起常驻服务。 */
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

        // 齿轮入口：进入设置页；左侧日记图标进入日志管理。
        toolbar.inflateMenu(R.menu.menu_main);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            if (item.getItemId() == R.id.action_logs) {
                startActivity(new Intent(this, LogActivity.class));
                return true;
            }
            return false;
        });

        // 今日值班人右侧的铅笔：直接记录今天的交接日志。
        findViewById(R.id.btn_today_log).setOnClickListener(v ->
                startActivity(LogEditActivity.intent(this, LocalDate.now().toEpochDay())));

        // 点击状态文字或提示文字，弹出手动设置窗口。
        tvStateValue.setOnClickListener(v -> showManualTransferDialog());
        tvStateTime.setOnClickListener(v -> showManualTransferDialog());

        // 应用更新或进程被杀后，重新打开 App 会恢复后台常驻服务与悬浮图标。
        if (AppPrefs.autoEnabled(this)) {
            MmiExecutor.startAgent(this);
        }

        recyclerCalendar = findViewById(R.id.recycler_calendar);
        recyclerCalendar.setLayoutManager(new GridLayoutManager(this, 7));
        // 关掉默认的条目动画：翻月时行高变化会让所有格子位移，
        // 默认动画会把这段位移演成 250ms 的"变形"，看起来像卡了一下。
        recyclerCalendar.setItemAnimator(null);
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

    /**
     * 回到前台时重读设置页可能改过的偏移量，刷新数据与状态，
     * 并补执行当天可能被系统丢掉的定时任务。
     */
    @Override
    protected void onResume() {
        super.onResume();
        // 偏移量可能在设置页被改过，每次回到前台都重新读取。
        offsetM = AppPrefs.offsetM(this);
        offsetW = AppPrefs.offsetW(this);
        offsetD = AppPrefs.offsetD(this);
        registerStateReceiver();
        loadData();
        renderState();

        // 国内 ROM 常把后台冻结，定时广播可能被丢弃；打开 App 时补执行当天漏掉的任务。
        if (AppPrefs.autoEnabled(this)) {
            io.execute(() -> AutoSwitchRunner.runIfDue(getApplicationContext()));
        }
    }

    /** 离开前台时注销状态广播，避免接收器泄漏。 */
    @Override
    protected void onPause() {
        super.onPause();
        unregisterStateReceiver();
    }

    /** 后台执行完成后主动刷新首页状态，保证“后台切了，前台看得到”。 */
    private void registerStateReceiver() {
        if (stateReceiver != null) return;
        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                renderState();
                String result = prefs.getString(AppPrefs.LAST_RESULT, "");
                String error = prefs.getString(AppPrefs.LAST_ERROR, "");
                if ("fail".equals(result) && !TextUtils.isEmpty(error)) {
                    Toast.makeText(MainActivity.this, "转移失败：" + error, Toast.LENGTH_LONG).show();
                }
            }
        };
        ContextCompat.registerReceiver(this, stateReceiver,
                new IntentFilter(AppPrefs.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    /** 注销状态广播，没有注册过就直接返回。 */
    private void unregisterStateReceiver() {
        if (stateReceiver == null) return;
        try {
            unregisterReceiver(stateReceiver);
        } catch (Exception ignored) {
        }
        stateReceiver = null;
    }

    /** 后台线程读今日值班与当月日历数据，再切回主线程刷新界面。 */
    private void loadData() {
        io.execute(() -> {
            LocalDate today = LocalDate.now();
            long day = today.toEpochDay();
            List<Person> people = db.personDao().getAll();
            ScheduleVersion todayVersion = db.scheduleDao().findVersionForDay(day);
            DayOverride todayOverride = db.scheduleDao().findOverride(day);
            Person p = ScheduleEngine.resolve(people, todayVersion, todayOverride, today, offsetM, offsetW, offsetD);
            AppPrefs.cacheToday(this, p == null ? "" : p.name, p == null ? "" : p.phoneNumber);

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

                submitCalendar(calendarItems);
                updateActionButton();
            });
        });
    }

    /** 按当前显示的月份重新生成日历数据。 */
    private void refreshCalendarView() {
        io.execute(() -> {
            List<Person> people = db.personDao().getAll();
            List<MonthCalendarAdapter.DayItem> calendarItems = buildCalendarData(displayMonth, people);
            runOnUiThread(() -> submitCalendar(calendarItems));
        });
    }

    /**
     * 一次性提交日历数据：行高和数据同一次通知，渲染出来就是最终尺寸。
     *
     * 先按新行数把行高算好再交给适配器，不会出现"先用旧行高画新月份、
     * 撑出底部再缩回来"的情况。代价是点击后可能慢一帧，换来翻页不闪。
     */
    private void submitCalendar(List<MonthCalendarAdapter.DayItem> items) {
        int weeks = (items.size() + 6) / 7;
        int row = computeRowHeight(weeks);
        if (row > 0) {
            calendarAdapter.submit(items, row);
        } else {
            calendarAdapter.submit(items);
        }
        // 布局稳定后再校正一次：冷启动首帧的位置可能还没定型。
        requestCalendarRowHeight();
    }

    /**
     * 按可用高度算出每行该多高；布局未稳定时返回 0。
     *
     * 只能基于「视口预算」计算：整屏减去上方两张卡片和底部提示，再减去卡片内边距与表头。
     * 不能用卡片高度——卡片是 wrap_content + weight，格一高卡片就跟着高，会正反馈失控；
     * 也不能用 RecyclerView 的 top——内容垂直居中，行高一变它就动，同样会来回抖。
     */
    private int computeRowHeight(int weeks) {
        if (weeks <= 0 || recyclerCalendar == null) return 0;
        View card = findViewById(R.id.card_calendar);
        View scroll = findViewById(R.id.scroll_content);
        View hint = findViewById(R.id.tv_calendar_hint);
        if (card == null || scroll == null || hint == null) return 0;
        if (scroll.getHeight() <= 0 || hint.getHeight() <= 0 || card.getTop() <= 0) return 0;
        if (!(recyclerCalendar.getParent() instanceof ViewGroup)) return 0;
        ViewGroup holder = (ViewGroup) recyclerCalendar.getParent();   // 网格外层 FrameLayout
        if (!(holder.getParent() instanceof ViewGroup)) return 0;
        ViewGroup inner = (ViewGroup) holder.getParent();              // 卡片内层 LinearLayout

        // 表头（月份标题 + 星期行）是 holder 之前的兄弟节点，高度固定
        int headerHeight = 0;
        for (int i = 0; i < inner.getChildCount(); i++) {
            View child = inner.getChildAt(i);
            if (child == holder) break;
            headerHeight += child.getHeight();
        }
        if (headerHeight <= 0) return 0;

        int hintBlock = hint.getHeight();
        ViewGroup.LayoutParams hintLp = hint.getLayoutParams();
        if (hintLp instanceof ViewGroup.MarginLayoutParams) {
            hintBlock += ((ViewGroup.MarginLayoutParams) hintLp).topMargin;
        }

        int cardSpace = scroll.getHeight() - scroll.getPaddingBottom()
                - card.getTop() - hintBlock;
        int gridSpace = cardSpace - inner.getPaddingTop() - inner.getPaddingBottom()
                - headerHeight
                - recyclerCalendar.getPaddingTop() - recyclerCalendar.getPaddingBottom();
        if (gridSpace <= 0) return 0;

        int row = gridSpace / weeks;
        // 下限 32dp：再小就两行文字放不下，宁可滚动。
        return Math.max(dp(32), Math.min(dp(58), row));
    }

    /**
     * 让日历行高跟随卡片可用高度变化：
     * 空间富余 → 行距拉大，把卡片填满；空间不足 → 保持最小行高，让页面滚动，不压扁内容。
     */
    private void requestCalendarRowHeight() {
        final View card = findViewById(R.id.card_calendar);
        if (card == null || recyclerCalendar == null) return;

        if (!calendarHeightHooked) {
            calendarHeightHooked = true;
            card.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if ((bottom - top) != (oldBottom - oldTop)) {
                    applyCalendarRowHeight(card);
                }
            });

            // 冷启动时首帧位置还没定型，等整棵视图布局完成后再校正一次。
            final View content = findViewById(R.id.scroll_content);
            if (content != null) {
                content.getViewTreeObserver().addOnGlobalLayoutListener(
                        () -> applyCalendarRowHeight(card));
            }
        }
        card.post(() -> applyCalendarRowHeight(card));
    }

    /** 按可用高度重算日历每行的高度；算不出来就保持现状，等下一次布局。 */
    private void applyCalendarRowHeight(View card) {
        int weeks = (calendarAdapter.getItemCount() + 6) / 7;
        if (weeks <= 0) return;

        int row = computeRowHeight(weeks);
        if (row <= 0) return;
        calendarAdapter.setRowHeight(row);
    }

    /** 把 dp 换算成当前屏幕密度的像素值。 */
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * 生成整月的日历格子：月初前的空位与每天的值班人。
     *
     * 在 IO 线程调用，内部直接查库。
     */
    private List<MonthCalendarAdapter.DayItem> buildCalendarData(LocalDate targetMonth, List<Person> people) {
        List<MonthCalendarAdapter.DayItem> items = new ArrayList<>();
        LocalDate firstOfMonth = targetMonth.withDayOfMonth(1);
        LocalDate lastOfMonth = targetMonth.withDayOfMonth(targetMonth.lengthOfMonth());
        LocalDate today = LocalDate.now();

        tvCalendarTitle.setText(targetMonth.getYear() + "年" + targetMonth.getMonthValue() + "月");

        // 一次查出本月所有有日志的日期，避免逐天查库。
        Set<Long> logDays = new HashSet<>(db.logDao().getDaysBetween(
                firstOfMonth.toEpochDay(), lastOfMonth.toEpochDay()));

        // 排班版本与单日换班也各查一次，在内存里按天匹配；
        // 逐天查库一个月要跑六十多次，翻页会有明显延迟。
        List<ScheduleVersion> versions = db.scheduleDao().getAllVersions();
        Map<Long, DayOverride> overrideMap = new HashMap<>();
        for (DayOverride o : db.scheduleDao().getOverridesBetween(
                firstOfMonth.toEpochDay(), lastOfMonth.toEpochDay())) {
            overrideMap.put(o.epochDay, o);
        }

        int startDayValue = firstOfMonth.getDayOfWeek().getValue();
        for (int i = 1; i < startDayValue; i++) {
            items.add(new MonthCalendarAdapter.DayItem());
        }

        for (LocalDate d = firstOfMonth; !d.isAfter(lastOfMonth); d = d.plusDays(1)) {
            MonthCalendarAdapter.DayItem item = new MonthCalendarAdapter.DayItem();
            item.date = d;
            item.isToday = d.equals(today);
            item.hasLog = logDays.contains(d.toEpochDay());

            ScheduleVersion v = pickVersion(versions, d.toEpochDay());
            DayOverride override = overrideMap.get(d.toEpochDay());
            item.person = ScheduleEngine.resolve(people, v, override, d, offsetM, offsetW, offsetD);

            items.add(item);
        }
        return items;
    }

    /** 从已排好序的版本列表里挑出当天生效的那一版（列表按生效日倒序）。 */
    private static ScheduleVersion pickVersion(List<ScheduleVersion> versions, long day) {
        if (versions == null) return null;
        for (ScheduleVersion v : versions) {
            if (v.effectiveFromEpochDay <= day) return v;
        }
        return null;
    }

    /** 点击日历某天：取人员、当天日志，弹出详情（值班人 + 日志 + 换人入口）。 */
    private void onCalendarDayClick(MonthCalendarAdapter.DayItem item) {
        io.execute(() -> {
            List<Person> people = db.personDao().getAll();
            DutyLog log = db.logDao().getByDay(item.date.toEpochDay());
            runOnUiThread(() -> showDayDetailDialog(item, people, log));
        });
    }

    /** 某天的详情弹窗：显示当天值班人与日志内容，并提供换人 / 编辑日志入口。 */
    private void showDayDetailDialog(final MonthCalendarAdapter.DayItem item,
                                     final List<Person> people,
                                     final DutyLog log) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_day_detail, null);
        TextView tvDate = view.findViewById(R.id.tv_detail_date);
        TextView tvPerson = view.findViewById(R.id.tv_detail_person);
        TextView tvLog = view.findViewById(R.id.tv_detail_log);
        View btnOverride = view.findViewById(R.id.btn_detail_override);
        MaterialButton btnClose = view.findViewById(R.id.btn_detail_close);
        MaterialButton btnEdit = view.findViewById(R.id.btn_detail_edit);

        tvPerson.setText(item.person == null ? "当天没有排班人员" : item.person.name);
        boolean hasLog = log != null && !TextUtils.isEmpty(log.content);
        tvLog.setText(hasLog ? log.content : "还没有日志，点下面「编辑日志」记一笔");
        if (!hasLog) {
            tvLog.setTextColor(getResources().getColor(R.color.md_on_surface_variant));
        }

        String[] weekdays = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
        String title = (item.date.getMonthValue()) + "月" + item.date.getDayOfMonth() + "日 "
                + weekdays[item.date.getDayOfWeek().getValue() - 1];
        tvDate.setText(title);

        // 用自绘 Dialog：Material 弹窗自带的内容面板会把底板颜色盖掉，
        // 深色模式下那层灰底压不住按钮，这里完全由布局自己决定外观。
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(view);
        Window dw = dialog.getWindow();
        if (dw != null) {
            dw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dw.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            
            dw.setDimAmount(0.55f);
            dw.setLayout(dp(320), WindowManager.LayoutParams.WRAP_CONTENT);
            dw.setGravity(Gravity.CENTER);
        }
        dialog.show();

        btnClose.setOnClickListener(v -> dialog.dismiss());
        btnEdit.setOnClickListener(v -> {
            dialog.dismiss();
            startActivity(LogEditActivity.intent(this, item.date.toEpochDay()));
        });
        btnOverride.setOnClickListener(v -> {
            dialog.dismiss();
            showOverrideDialog(item, people);
        });
    }

    /** 弹出某天的临时换人对话框，确认后写入或删除当天的覆盖记录。 */
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
            // id 不能为 0（0 即 View.NO_ID），否则单选组会判定为未选中。
            rb.setId(i + 1);
            rb.setTextSize(16);
            rb.setPadding(0, 24, 0, 24);
            if (i == 0) {
                rb.setText("清除临时换人（恢复规则）");
            } else {
                rb.setText(people.get(i - 1).name);
                if (item.person != null && people.get(i - 1).id == item.person.id) {
                    checkedId = i + 1;
                }
            }
            rg.addView(rb);
        }
        if (checkedId != -1) rg.check(checkedId);

        AlertDialog dialog = new com.eafnv.callforward.ui.DutyDialogBuilder(this)
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
                final int index = selectedId - 1;
                io.execute(() -> {
                    if (index == 0) {
                        db.scheduleDao().deleteOverride(item.date.toEpochDay());
                    } else {
                        DayOverride o = new DayOverride();
                        o.epochDay = item.date.toEpochDay();
                        o.personId = people.get(index - 1).id;
                        db.scheduleDao().upsertOverride(o);
                    }
                    runOnUiThread(this::loadData);
                });
            }
        });
    }

    /** 弹出手动输入号码的对话框，校验通过后发起转移。 */
    private void showManualTransferDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_manual_transfer, null);
        EditText etNumber = view.findViewById(R.id.et_manual_number);
        MaterialButton btnCancel = view.findViewById(R.id.btn_cancel);
        MaterialButton btnConfirm = view.findViewById(R.id.btn_confirm);

        // 如果上次是手动设置的，回显号码。
        if (prefs.getBoolean(KEY_IS_MANUAL, false)) {
            etNumber.setText(prefs.getString(KEY_LAST_PHONE, ""));
        }

        AlertDialog dialog = new com.eafnv.callforward.ui.DutyDialogBuilder(this)
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
            // 号码校验。
            if (!number.matches("^1[3-9]\\d{9}$")) {
                Toast.makeText(this, "请输入正确的 11 位手机号码", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            // 执行手动转移。
            executeManualTransfer(number);
        });
    }

    /** 把手动输入的号码交给执行器下发。 */
    private void executeManualTransfer(final String number) {
        runTransfer(MmiExecutor.setCode(number), true, "手动号码", number, true);
    }

    /** 按偏好设置里的转移状态刷新首页状态文字，并同步底部按钮。 */
    private void renderState() {
        boolean isSet = prefs.getBoolean(KEY_LAST_IS_SET, false);
        boolean isManual = prefs.getBoolean(KEY_IS_MANUAL, false);
        String name = prefs.getString(KEY_LAST_PERSON, "");
        String phone = prefs.getString(KEY_LAST_PHONE, "");
        long time = prefs.getLong(KEY_LAST_TIME, 0);
        String result = prefs.getString(AppPrefs.LAST_RESULT, "");
        String error = prefs.getString(AppPrefs.LAST_ERROR, "");

        if (AppPrefs.RESULT_FAIL.equals(result)) {
            // 最近一次操作失败：不能让界面继续显示“已转移”，否则会误判
            tvStateValue.setText("转移操作失败");
            tvStateTime.setText(TextUtils.isEmpty(error)
                    ? "点击此处可重试"
                    : error + " · 点击此处可重试");
            tvStateTime.setVisibility(TextView.VISIBLE);
        } else if (AppPrefs.RESULT_UNKNOWN.equals(result)) {
            // 指令发出去了，但回执看不出成败
            tvStateValue.setText(describeTarget("已发起转移至", isManual, name, phone));
            tvStateTime.setText("运营商未给出明确回执，请留意是否生效 · 点击此处可修改");
            tvStateTime.setVisibility(TextView.VISIBLE);
        } else if (isSet) {
            tvStateValue.setText(describeTarget("已转移至", isManual, name, phone));
            if (time > 0) {
                // 已设置时，小字显示操作时间，并暗示可修改。
                tvStateTime.setText("上次操作：" + formatTime(time) + " · 点击此处可修改");
                tvStateTime.setVisibility(TextView.VISIBLE);
            }
        } else {
            tvStateValue.setText("未设置转移");
            // 未设置时，小字作为引导提示。
            tvStateTime.setText("点击此处可手动指定号码");
            tvStateTime.setVisibility(TextView.VISIBLE);
        }
        updateActionButton();
    }

    /**
     * 拼出「已转移至 X」这类状态文案，X 的取值按优先级回退。
     *
     * 优先人名，其次号码；两者都没有时只保留前半句，
     * 避免出现「已发起转移至 」这种尾巴悬空的情况。
     */
    private String describeTarget(String prefix, boolean isManual, String name, String phone) {
        if (isManual && !TextUtils.isEmpty(phone)) return prefix + "手动号码 " + phone;
        if (!TextUtils.isEmpty(name)) return prefix + " " + name;
        if (!TextUtils.isEmpty(phone)) return prefix + " " + phone;
        return prefix.replace("至", "");
    }

    /** 根据是否已设置转移、今日是否有排班，决定底部按钮的文案与点击行为。 */
    private void updateActionButton() {
        boolean isSet = prefs.getBoolean(KEY_LAST_IS_SET, false);
        boolean isManual = prefs.getBoolean(KEY_IS_MANUAL, false);
        String lastPhone = prefs.getString(KEY_LAST_PHONE, "");
        // 上次失败时，把按钮文案改造成“重试”，让用户知道可以直接再来一次
        boolean retry = AppPrefs.RESULT_FAIL.equals(prefs.getString(AppPrefs.LAST_RESULT, ""));
        String switchText = retry ? "重试切换至今日值班" : "一键切换至今日值班";
        String cancelText = retry ? "重试取消转移" : "取消转移";

        if (isSet) {
            // 已经设置过转移，按今日排班与当前号码的关系分三种情况。
            if (todayPerson != null && !isManual && lastPhone.equals(todayPerson.phoneNumber)) {
                // 场景A：转移给了今日排班人，按钮变“取消”。
                btnAction.setText(cancelText);
                btnAction.setOnClickListener(v -> onCancelClick());
            } else if (todayPerson != null) {
                // 场景B：有排班，但当前转移给了别人（比如昨天的人或手动号码），按钮变“切换”。
                btnAction.setText(switchText);
                btnAction.setOnClickListener(v -> onSwitchClick());
            } else {
                // 场景C：今天没排班，但设置了转移（必然是手动设置的），按钮必须能“取消”。
                btnAction.setText(cancelText);
                btnAction.setOnClickListener(v -> onCancelClick());
            }
            return; // 设置过转移的情况处理完了，直接结束。
        }

        // 没设置转移时，按今日是否有排班分两种情况。
        if (todayPerson == null) {
            // 场景D：没设置转移，且今天没排班。
            btnAction.setText("前往排班管理");
            btnAction.setOnClickListener(v -> startActivity(new Intent(this, ScheduleActivity.class)));
        } else {
            // 场景E：没设置转移，且今天有排班。
            btnAction.setText(switchText);
            btnAction.setOnClickListener(v -> onSwitchClick());
        }
    }

    /** 把毫秒时间戳格式化成状态区显示的时间。 */
    private String formatTime(long epochMillis) {
        LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
        return dt.format(TIME_FMT);
    }

    /** 一键切换到今日值班人。 */
    private void onSwitchClick() {
        if (todayPerson == null) return;
        runTransfer(MmiExecutor.setCode(todayPerson.phoneNumber), true,
                todayPerson.name, todayPerson.phoneNumber, false);
    }

    /** 取消当前的转移设置。 */
    private void onCancelClick() {
        runTransfer(MmiExecutor.CANCEL_CODE, false, null, null, false);
    }

    /**
     * 所有前台操作都从这里进入执行器，不再直接拨号。
     * 真正的执行由 MmiService 完成，状态由 USSD 回调写回。
     */
    private void runTransfer(final String code, final boolean isSet,
                             final String person, final String phone, final boolean manual) {
        if (!hasCallPermission()) {
            pendingAction = () -> dispatch(code, isSet, person, phone, manual);
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CALL_PHONE}, REQ_CALL_PHONE);
            return;
        }
        dispatch(code, isSet, person, phone, manual);
    }

    /** 执行器忙时提示稍候，空闲时下发指令。 */
    private void dispatch(String code, boolean isSet, String person, String phone, boolean manual) {
        if (MmiExecutor.isBusy()) {
            Toast.makeText(this, "正在执行上一条指令，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        MmiExecutor.execute(this, code, isSet, person, phone, manual, false);
    }

    /** 是否已经拿到拨号权限。 */
    private boolean hasCallPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 权限回调：用户同意后补执行挂起的操作。 */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CALL_PHONE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Runnable action = pendingAction;
                pendingAction = null;
                if (action != null) action.run();
            } else {
                Toast.makeText(this, "需要拨号权限才能执行转移", Toast.LENGTH_LONG).show();
            }
        }
    }
}