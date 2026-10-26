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

public class PersonAdapter extends RecyclerView.Adapter<PersonAdapter.VH> {

    public interface OnItemClick {
        void onClick(Person person);
    }

    public interface OnOrderChanged {
        void onChanged(List<Person> newOrder);
    }

    private final List<Person> data = new ArrayList<>();
    private OnItemClick onClick;
    private OnOrderChanged onOrderChanged;

    public void setOnItemClick(OnItemClick l) { this.onClick = l; }
    public void setOnOrderChanged(OnOrderChanged l) { this.onOrderChanged = l; }

    public void submit(List<Person> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    public Person getItem(int position) { return data.get(position); }

    // 拖拽时交换两项，并通知界面 */
    public void onMove(int from, int to) {
        Collections.swap(data, from, to);
        notifyItemMoved(from, to);
    }

    // 拖拽结束后回调新的顺序
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

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvNumber, tvCarrier;
        ImageView ivDrag;
        VH(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_name);
            tvNumber = itemView.findViewById(R.id.tv_number);
            tvCarrier = itemView.findViewById(R.id.tv_carrier);
            ivDrag = itemView.findViewById(R.id.iv_drag);
        }
    }
}