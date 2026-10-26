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

public class SchedulePreviewAdapter extends RecyclerView.Adapter<SchedulePreviewAdapter.VH> {

    public interface OnDayClick {
        void onClick(Row row);
    }

    public static class Row {
        public LocalDate date;
        public Person person;
        public boolean isOverride;
        public boolean isToday;
    }

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("M月d日 EEEE");

    private final List<Row> rows = new ArrayList<>();
    private OnDayClick onClick;

    public void setOnDayClick(OnDayClick l) { this.onClick = l; }

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