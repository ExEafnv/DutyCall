package com.eafnv.callforward.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.eafnv.callforward.R;
import com.eafnv.callforward.data.Formula;

import java.util.ArrayList;
import java.util.List;

/**
 * 公式列表适配器，展示公式备注与表达式，并转发点击、删除事件。
 *
 * 选择模式下由外部隐藏删除按钮。
 */
public class FormulaAdapter extends RecyclerView.Adapter<FormulaAdapter.VH> {

    /** 公式条目的点击与删除回调。 */
    public interface Listener {
        void onClick(Formula formula);

        void onDelete(Formula formula);
    }

    private final List<Formula> items = new ArrayList<>();
    private Listener listener;
    private boolean showDelete = true;

    /** 设置条目交互回调。 */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 控制删除按钮是否显示，选择模式下会隐藏。 */
    public void setShowDelete(boolean show) {
        this.showDelete = show;
    }

    /** 替换数据并刷新列表。 */
    public void submit(List<Formula> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_formula, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Formula formula = items.get(position);
        h.tvName.setText(formula.name == null || formula.name.isEmpty() ? "未命名公式" : formula.name);
        h.tvExpr.setText(formula.expression);

        h.btnDelete.setVisibility(showDelete ? View.VISIBLE : View.GONE);
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(formula);
        });
        h.btnDelete.setOnClickListener(v -> {
            if (listener != null) listener.onDelete(formula);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /** 公式条目的视图引用。 */
    static class VH extends RecyclerView.ViewHolder {
        final TextView tvName;
        final TextView tvExpr;
        final TextView btnDelete;

        VH(@NonNull View v) {
            super(v);
            tvName = v.findViewById(R.id.tv_formula_name);
            tvExpr = v.findViewById(R.id.tv_formula_expr);
            btnDelete = v.findViewById(R.id.btn_delete);
        }
    }
}
