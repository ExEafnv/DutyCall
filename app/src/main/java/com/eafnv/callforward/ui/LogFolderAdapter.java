package com.eafnv.callforward.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.eafnv.callforward.R;
import com.eafnv.callforward.data.LogFolder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 归档视图里的文件夹列表适配器，支持长按多选。
 */
public class LogFolderAdapter extends RecyclerView.Adapter<LogFolderAdapter.VH> {

    /** 一个归档目录，附带里面的日志数量与最近日期。 */
    public static class Item {
        public LogFolder folder;
        public int count;
        public long latestDay;
    }

    /** 点击与长按回调。 */
    public interface Listener {
        void onClick(Item item);

        void onLongClick(Item item);
    }

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

    /** 进入或退出多选模式。 */
    public void setSelectionMode(boolean enabled) {
        if (selectionMode == enabled) return;
        selectionMode = enabled;
        if (!enabled) selected.clear();
        notifyDataSetChanged();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public int selectedCount() {
        return selected.size();
    }

    public List<Long> selectedIds() {
        return new ArrayList<>(selected);
    }

    /** 当前选中的唯一目录；多选时返回 null。 */
    public Item singleSelected() {
        if (selected.size() != 1) return null;
        long id = selected.iterator().next();
        for (Item item : items) {
            if (item.folder.id == id) return item;
        }
        return null;
    }

    public void toggle(long folderId) {
        if (!selected.remove(folderId)) selected.add(folderId);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log_folder, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Item item = items.get(position);
        h.tvName.setText(item.folder.name == null ? "未命名归档" : item.folder.name);

        StringBuilder meta = new StringBuilder(item.count + " 条日志");
        if (item.latestDay > 0) {
            meta.append(" · 最近 ").append(com.eafnv.callforward.util.LogSearch
                    .describeDay(item.latestDay));
        }
        h.tvMeta.setText(meta);

        h.cb.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        // 固定配色的勾选图标：深浅色模式外观一致
        h.cb.setImageResource(selected.contains(item.folder.id)
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
        final TextView tvName;
        final TextView tvMeta;
        final android.widget.ImageView cb;

        VH(@NonNull View v) {
            super(v);
            tvName = v.findViewById(R.id.tv_folder_name);
            tvMeta = v.findViewById(R.id.tv_folder_meta);
            cb = v.findViewById(R.id.cb_item);
        }
    }
}
