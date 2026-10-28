package com.eafnv.callforward.ui;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eafnv.callforward.R;
import com.eafnv.callforward.data.AppDatabase;
import com.eafnv.callforward.data.Person;
import com.eafnv.callforward.util.CarrierUtils;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 人员管理页，支持新增、编辑、删除与拖拽排序。
 *
 * 排序结果按排序字段持久化，供排班引擎按名单顺序轮转。
 */
public class PeopleActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private AppDatabase db;
    private PersonAdapter adapter;
    private TextView tvEmpty;
    private RecyclerView recycler;

    private final String[] carrierLabels = {"自动推断", "移动", "联通", "电信"};
    private final String[] carrierKeys = {null, CarrierUtils.CMCC, CarrierUtils.UNICOM, CarrierUtils.TELECOM};

    /** 初始化人员列表、拖拽排序与新增入口。 */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_people);

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

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvEmpty = findViewById(R.id.tv_empty);
        recycler = findViewById(R.id.recycler);
        adapter = new PersonAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        adapter.setOnItemClick(this::showEditDialog);
        adapter.setOnOrderChanged(this::persistOrder);
        setupDrag();

        FloatingActionButton fab = findViewById(R.id.fab_add);
        fab.setOnClickListener(v -> showEditDialog(null));

        loadPeople();
    }

    /** 配置拖拽排序，只允许从拖动手柄开始拖动。 */
    private void setupDrag() {
        ItemTouchHelper.Callback callback = new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(RecyclerView rv, RecyclerView.ViewHolder vh) {
                int dragFlags = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
                return makeMovementFlags(dragFlags, 0);
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override
            public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder src, RecyclerView.ViewHolder dst) {
                adapter.onMove(src.getBindingAdapterPosition(), dst.getBindingAdapterPosition());
                return true;
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder vh, int direction) { }

            @Override
            public void clearView(RecyclerView rv, RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                adapter.notifyOrderChanged();
            }
        };
        ItemTouchHelper helper = new ItemTouchHelper(callback);
        helper.attachToRecyclerView(recycler);

        recycler.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(View view) {
                View handle = view.findViewById(R.id.iv_drag);
                if (handle != null) {
                    handle.setOnTouchListener((v, event) -> {
                        if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                            RecyclerView.ViewHolder vh = recycler.findContainingViewHolder(view);
                            if (vh != null) helper.startDrag(vh);
                        }
                        return false;
                    });
                }
            }
            @Override
            public void onChildViewDetachedFromWindow(View view) { }
        });
    }

    /** 异步加载人员并刷新空状态。 */
    private void loadPeople() {
        io.execute(() -> {
            List<Person> list = db.personDao().getAll();
            runOnUiThread(() -> {
                adapter.submit(list);
                tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    /** 新增或编辑人员，包括姓名、号码与运营商选择。 */
    private void showEditDialog(Person person) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_person, null);
        EditText etName = view.findViewById(R.id.et_name);
        EditText etNumber = view.findViewById(R.id.et_number);
        AutoCompleteTextView actvCarrier = view.findViewById(R.id.actv_carrier);

        // 自定义底部按钮，便于单独控制删除入口。
        com.google.android.material.button.MaterialButton btnDelete = view.findViewById(R.id.btn_delete);
        com.google.android.material.button.MaterialButton btnCancel = view.findViewById(R.id.btn_cancel);
        com.google.android.material.button.MaterialButton btnSave = view.findViewById(R.id.btn_save);

        ArrayAdapter<String> dropAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, carrierLabels);
        actvCarrier.setAdapter(dropAdapter);

        int initialIndex = 0;
        if (person != null) {
            etName.setText(person.name);
            etNumber.setText(person.phoneNumber);
            for (int i = 0; i < carrierKeys.length; i++) {
                if (TextUtils.equals(carrierKeys[i], person.carrierOverride)) {
                    initialIndex = i;
                    break;
                }
            }
            // 编辑模式下显示删除按钮。
            btnDelete.setVisibility(View.VISIBLE);
        }
        actvCarrier.setText(carrierLabels[initialIndex], false);

        com.google.android.material.dialog.MaterialAlertDialogBuilder builder =
                new com.eafnv.callforward.ui.DutyDialogBuilder(this)
                        .setTitle(person == null ? "新增值班人员" : "编辑值班人员")
                        .setView(view)
                        // 传入 null 去掉系统自带的底部按钮。
                        .setPositiveButton(null, null)
                        .setNegativeButton(null, null)
                        .setNeutralButton(null, null);

        AlertDialog dialog = builder.create();
        dialog.show();

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnSave.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            String number = etNumber.getText().toString().trim();
            if (TextUtils.isEmpty(name) || TextUtils.isEmpty(number)) {
                Toast.makeText(this, "姓名和号码不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            String selected = actvCarrier.getText().toString();
            int idx = 0;
            for (int i = 0; i < carrierLabels.length; i++) {
                if (carrierLabels[i].equals(selected)) { idx = i; break; }
            }
            savePerson(person, name, number, carrierKeys[idx]);
            dialog.dismiss();
        });

        btnDelete.setOnClickListener(v -> {
            dialog.dismiss();
            confirmDelete(person);
        });
    }

    /** 二次确认后删除人员。 */
    private void confirmDelete(Person person) {
        android.view.View view = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_confirm, null);
        android.widget.TextView tvMessage = view.findViewById(R.id.tv_message);
        com.google.android.material.button.MaterialButton btnCancel = view.findViewById(R.id.btn_cancel);
        com.google.android.material.button.MaterialButton btnConfirm = view.findViewById(R.id.btn_confirm);

        tvMessage.setText("确定删除「" + person.name + "」吗？");

        com.google.android.material.dialog.MaterialAlertDialogBuilder builder =
                new com.eafnv.callforward.ui.DutyDialogBuilder(this)
                        .setTitle("删除人员")
                        .setView(view)
                        .setPositiveButton(null, null)
                        .setNegativeButton(null, null);

        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.show();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            dialog.dismiss();
            io.execute(() -> {
                db.personDao().delete(person);
                runOnUiThread(this::loadPeople);
            });
        });
    }

    /** 新增或更新人员，新增时排到名单末尾。 */
    private void savePerson(Person existing, String name, String number, String carrier) {
        io.execute(() -> {
            if (existing == null) {
                Integer max = db.personDao().maxSortOrder();
                Person p = new Person(name, number);
                p.sortOrder = (max == null ? 0 : max + 1);
                p.carrierOverride = carrier;
                db.personDao().insert(p);
            } else {
                existing.name = name;
                existing.phoneNumber = number;
                existing.carrierOverride = carrier;
                db.personDao().update(existing);
            }
            runOnUiThread(this::loadPeople);
        });
    }

    /** 按新顺序写入排序字段。 */
    private void persistOrder(List<Person> newOrder) {
        io.execute(() -> {
            for (int i = 0; i < newOrder.size(); i++) {
                Person p = newOrder.get(i);
                p.sortOrder = i;
                db.personDao().update(p);
            }
        });
    }
}