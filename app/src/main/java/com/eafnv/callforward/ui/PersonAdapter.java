package com.eafnv.callforward.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.eafnv.callforward.R;
import com.eafnv.callforward.data.Person;
import com.eafnv.callforward.util.CarrierUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 人员列表适配器，展示序号、姓名、号码与运营商，并支持拖拽排序。
 *
 * 序号跟随列表位置实时变化，拖拽结束后把新顺序回调给页面持久化。
 */
public class PersonAdapter extends RecyclerView.Adapter<PersonAdapter.VH> {

    /** 人员条目的点击回调。 */
    public interface OnItemClick {
        void onClick(Person person);
    }

    /** 拖拽排序后的顺序回调。 */
    public interface OnOrderChanged {
        void onChanged(List<Person> newOrder);
    }

    private final List<Person> data = new ArrayList<>();
    private OnItemClick onClick;
    private OnOrderChanged onOrderChanged;

    /** 设置条目点击回调。 */
    public void setOnItemClick(OnItemClick l) { this.onClick = l; }
    /** 设置排序变化回调。 */
    public void setOnOrderChanged(OnOrderChanged l) { this.onOrderChanged = l; }

    /** 替换人员数据并刷新列表。 */
    public void submit(List<Person> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    public Person getItem(int position) { return data.get(position); }

    /** 拖拽时交换两项并刷新区间内的序号。 */
    public void onMove(int from, int to) {
        Collections.swap(data, from, to);
        notifyItemMoved(from, to);

        // 强制刷新起止位置之间的所有项，让编号实时更新。
        int start = Math.min(from, to);
        int end = Math.max(from, to);
        notifyItemRangeChanged(start, end - start + 1);
    }

    /** 拖拽结束后把新顺序回调给页面。 */
    public void notifyOrderChanged() {
        if (onOrderChanged != null) onOrderChanged.onChanged(new ArrayList<>(data));
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_person, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Person p = data.get(position);

        // 序号跟随当前列表位置，拖动后会实时更新。
        holder.tvPersonNumber.setText("M" + (position + 1));

        holder.tvName.setText(p.name);
        holder.tvNumber.setText(p.phoneNumber);
        String carrier = p.carrierOverride != null
                ? p.carrierOverride
                : CarrierUtils.guessCarrier(p.phoneNumber);
        holder.tvCarrier.setText(CarrierUtils.displayName(carrier));
        holder.itemView.setOnClickListener(v -> {
            if (onClick != null) onClick.onClick(p);
        });
    }

    @Override
    public int getItemCount() { return data.size(); }

    /** 人员条目的视图引用。 */
    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvNumber, tvCarrier, tvPersonNumber;
        ImageView ivDrag;
        VH(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_name);
            tvNumber = itemView.findViewById(R.id.tv_number);
            tvCarrier = itemView.findViewById(R.id.tv_carrier);
            tvPersonNumber = itemView.findViewById(R.id.tv_person_number);
            ivDrag = itemView.findViewById(R.id.iv_drag);
        }
    }
}