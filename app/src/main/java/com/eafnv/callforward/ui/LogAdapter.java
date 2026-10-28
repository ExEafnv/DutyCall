package com.eafnv.callforward.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.eafnv.callforward.R;
import com.eafnv.callforward.data.DutyLog;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 日志列表适配器，支持长按进入多选。
 */
public class LogAdapter extends RecyclerView.Adapter<LogAdapter.VH> {

    /** 列表项：日志本体 + 当天值班人 + 所属目录名。 */
    public static class Item {
        public DutyLog log;
        public String personName;
        public String folderName;
    }

    /** 点击与长按回调。 */
    public interface Listener {
        void onClick(Item item);

        void onLongClick(Item item);
    }

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("M月d日");

    private final List<Item> items = new ArrayList<>();
    private final Set<Long> selected = new HashSet<>();
    private Listener listener;
    private boolean selectionMode;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Item> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    /** 进入或退出多选模式；退出时清空选中项。 */
    public void setSelectionMode(boolean enabled) {
        if (selectionMode == enabled) return;
        selectionMode = enabled;
        if (!enabled) selected.clear();
        notifyDataSetChanged();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    /** 已选中的日期集合，用于批量删除与归档。 */
    public List<Long> selectedDays() {
        return new ArrayList<>(selected);
    }

    public int selectedCount() {
        return selected.size();
    }

    /** 切换某一项的选中状态。 */
    public void toggle(long epochDay) {
        if (!selected.remove(epochDay)) selected.add(epochDay);
        notifyDataSetChanged();
    }

    /** 当前列表里全部日期，用于“全选”。 */
    public List<Long> allDays() {
        List<Long> days = new ArrayList<>();
        for (Item item : items) days.add(item.log.epochDay);
        return days;
    }

    /** 全选或取消全选。 */
    public void selectAll(boolean select) {
        selected.clear();
        if (select) {
            for (Item item : items) selected.add(item.log.epochDay);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_log, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Item item = items.get(position);

        LocalDate date = LocalDate.ofEpochDay(item.log.epochDay);
        String weekday = new String[]{"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"}
                [date.getDayOfWeek().getValue() - 1];
        h.tvDate.setText(date.format(DATE_FMT) + " " + weekday);

        if (item.folderName == null || item.folderName.isEmpty()) {
            h.tvFolder.setVisibility(View.GONE);
        } else {
            h.tvFolder.setVisibility(View.VISIBLE);
            h.tvFolder.setText(item.folderName);
        }

        String content = item.log.content == null ? "" : item.log.content.trim().replace("\n", "  ");
        h.tvContent.setText(content.isEmpty() ? "（空日志）" : content);

        StringBuilder meta = new StringBuilder();
        meta.append(item.personName == null || item.personName.isEmpty()
                ? "当天无排班" : "值班：" + item.personName);
        h.tvMeta.setText(meta);

        h.cb.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        // 固定配色的勾选图标：深色模式下也不会变成刺眼的亮青色
        h.cb.setImageResource(selected.contains(item.log.epochDay)
                ? R.drawable.ic_check_circle_on
                : R.drawable.ic_check_circle_off);

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(item);
        });
        h.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onLongClick(item);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /** 列表项视图引用。 */
    static class VH extends RecyclerView.ViewHolder {
        final TextView tvDate;
        final TextView tvFolder;
        final TextView tvContent;
        final TextView tvMeta;
        final android.widget.ImageView cb;

        VH(@NonNull View v) {
            super(v);
            tvDate = v.findViewById(R.id.tv_item_date);
            tvFolder = v.findViewById(R.id.tv_item_folder);
            tvContent = v.findViewById(R.id.tv_item_content);
            tvMeta = v.findViewById(R.id.tv_item_meta);
            cb = v.findViewById(R.id.cb_item);
        }
    }
}
