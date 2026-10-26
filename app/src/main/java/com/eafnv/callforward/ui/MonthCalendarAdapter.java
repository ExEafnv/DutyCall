package com.eafnv.callforward.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.eafnv.callforward.R;
import com.eafnv.callforward.data.Person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class MonthCalendarAdapter extends RecyclerView.Adapter<MonthCalendarAdapter.VH> {

    public interface OnDayClick {
        void onClick(DayItem item);
    }

    public static class DayItem {
        public LocalDate date;       // null 表示占位空白格
        public Person person;
        public boolean isToday;
    }

    private final List<DayItem> items = new ArrayList<>();
    private OnDayClick onDayClick;

    public void setOnDayClick(OnDayClick l) { this.onDayClick = l; }

    public void submit(List<DayItem> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_calendar_day, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        DayItem item = items.get(position);

        if (item.date == null) {
            // 空白占位格
            h.tvDayNumber.setText("");
            h.tvPersonShort.setVisibility(View.GONE);
            h.tvPersonShort.setSelected(false);
            h.itemView.setBackground(null);
            h.itemView.setOnClickListener(null);
            return;
        }

        h.tvDayNumber.setText(String.valueOf(item.date.getDayOfMonth()));

        if (item.person != null) {
            h.tvPersonShort.setVisibility(View.VISIBLE);
            String name = item.person.name;

            // 大于三个字时，前后加空格强制触发跑马灯滚动
            if (name != null && name.length() > 3) {
                h.tvPersonShort.setText("  " + name + "  ");
            } else {
                h.tvPersonShort.setText(name);
            }

            // 激活跑马灯
            h.tvPersonShort.setSelected(true);
        } else {
            h.tvPersonShort.setVisibility(View.GONE);
            h.tvPersonShort.setSelected(false);
        }

        // 今天的高亮
        if (item.isToday) {
            h.itemView.setBackgroundResource(R.drawable.bg_calendar_today);
            h.tvDayNumber.setTextColor(h.itemView.getContext().getColor(R.color.action_text));
            h.tvPersonShort.setTextColor(h.itemView.getContext().getColor(R.color.action_text));
        } else {
            h.itemView.setBackground(null);
            h.tvDayNumber.setTextColor(h.itemView.getContext().getColor(R.color.md_on_surface));
            h.tvPersonShort.setTextColor(h.itemView.getContext().getColor(R.color.md_primary));
        }

        h.itemView.setOnClickListener(v -> {
            if (onDayClick != null) onDayClick.onClick(item);
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvDayNumber, tvPersonShort;
        VH(@NonNull View v) {
            super(v);
            tvDayNumber = v.findViewById(R.id.tv_day_number);
            tvPersonShort = v.findViewById(R.id.tv_person_short);
        }
    }
}