package com.eafnv.callforward.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.eafnv.callforward.R;
import com.eafnv.callforward.data.Person;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 排班页「未来 14 天」列表的适配器。
 *
 * 每行是某一天的排班结果：日期、当天值班人，以及该天是否被手动换班（显示小角标）。
 * 点某一行会回调给页面，由页面弹出「临时换人」入口。
 */
public class SchedulePreviewAdapter extends RecyclerView.Adapter<SchedulePreviewAdapter.VH> {

    /** 点击某一天的回调。 */
    public interface OnDayClick {
        void onClick(Row row);
    }

    /** 一行排班数据：日期、当天人选，以及是否手动换班、是否今天。 */
    public static class Row {
        /** 这一行的日期。 */
        public LocalDate date;
        /** 当天值班人，没有排班时为 null。 */
        public Person person;
        /** 是否来自单日换班，用来决定是否显示角标。 */
        public boolean isOverride;
        /** 是否今天，决定是否加「今天 · 」前缀。 */
        public boolean isToday;
    }

    /** 日期显示格式，例如「10月28日 星期三」。 */
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("M月d日 EEEE");

    /** 当前展示的行，由外部整体替换。 */
    private final List<Row> rows = new ArrayList<>();
    /** 行点击回调。 */
    private OnDayClick onClick;

    /** 设置行点击回调。 */
    public void setOnDayClick(OnDayClick l) { this.onClick = l; }

    /** 整体替换列表数据。 */
    public void submit(List<Row> list) {
        rows.clear();
        if (list != null) rows.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_schedule_day, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Row row = rows.get(position);
        String dateText = row.date.format(FMT);
        if (row.isToday) dateText = "今天 · " + dateText;
        h.tvDate.setText(dateText);
        h.tvPerson.setText(row.person == null ? "—" : row.person.name);
        h.tvBadge.setVisibility(row.isOverride ? View.VISIBLE : View.GONE);
        h.itemView.setOnClickListener(v -> {
            if (onClick != null) onClick.onClick(row);
        });
    }

    @Override
    public int getItemCount() { return rows.size(); }

    /** 行视图的控件缓存。 */
    static class VH extends RecyclerView.ViewHolder {
        TextView tvDate, tvPerson, tvBadge;
        VH(@NonNull View v) {
            super(v);
            tvDate = v.findViewById(R.id.tv_date);
            tvPerson = v.findViewById(R.id.tv_person);
            tvBadge = v.findViewById(R.id.tv_badge);
        }
    }
}
