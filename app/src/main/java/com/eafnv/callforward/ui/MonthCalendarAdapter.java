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

/**
 * 月历格子适配器，展示日期、值班人与今日高亮。
 *
 * 行高可由外部按可用空间动态设置。
 */
public class MonthCalendarAdapter extends RecyclerView.Adapter<MonthCalendarAdapter.VH> {

    /** 日期格子的点击回调。 */
    public interface OnDayClick {
        void onClick(DayItem item);
    }

    /** 一个日期格子，日期为空时表示占位空白格。 */
    public static class DayItem {
        /** 日期，为空时表示占位空白格。 */
        public LocalDate date;
        public Person person;
        public boolean isToday;
        /** 当天是否有交接日志，有则数字变色并在右上角打点。 */
        public boolean hasLog;
    }

    private final List<DayItem> items = new ArrayList<>();
    private OnDayClick onDayClick;
    private int rowHeightPx;

    /** 设置日期格子的点击回调。 */
    public void setOnDayClick(OnDayClick l) { this.onDayClick = l; }

    /** 由外部按可用高度动态设置行高；0 表示使用布局里的默认值。 */
    public void setRowHeight(int px) {
        if (rowHeightPx == px) return;
        rowHeightPx = px;
        notifyDataSetChanged();
    }

    /** 当前行高，0 表示还用着布局里的默认值。 */
    public int getRowHeight() {
        return rowHeightPx;
    }

    /** 替换日期数据并刷新。 */
    public void submit(List<DayItem> list) {
        submit(list, 0);
    }

    /**
     * 替换数据的同时设置行高，只触发一次重绘。
     *
     * 翻月时用它把行高和内容一次性提交，避免"先按旧行高画一遍、再缩放一次"。
     */
    public void submit(List<DayItem> list, int rowHeightPx) {
        if (rowHeightPx > 0) this.rowHeightPx = rowHeightPx;
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

    /** 绑定日期、值班人与今日高亮。 */
    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        DayItem item = items.get(position);

        if (rowHeightPx > 0) {
            ViewGroup.LayoutParams lp = h.itemView.getLayoutParams();
            if (lp != null && lp.height != rowHeightPx) {
                lp.height = rowHeightPx;
                h.itemView.setLayoutParams(lp);
            }
        }

        if (item.date == null) {
            // 空白占位格。
            h.tvDayNumber.setText("");
            h.tvPersonShort.setVisibility(View.GONE);
            h.tvPersonShort.setSelected(false);
            h.dotLog.setVisibility(View.GONE);
            h.itemView.setBackground(null);
            h.itemView.setOnClickListener(null);
            return;
        }

        h.tvDayNumber.setText(String.valueOf(item.date.getDayOfMonth()));

        if (item.person != null) {
            h.tvPersonShort.setVisibility(View.VISIBLE);
            String name = item.person.name;

            // 姓名超过三个字时，前后补空格以触发跑马灯滚动。
            if (name != null && name.length() > 3) {
                h.tvPersonShort.setText("  " + name + "  ");
            } else {
                h.tvPersonShort.setText(name);
            }

            // 激活跑马灯滚动。
            h.tvPersonShort.setSelected(true);
        } else {
            h.tvPersonShort.setVisibility(View.GONE);
            h.tvPersonShort.setSelected(false);
        }

        // 今天用圆形背景高亮。
        if (item.isToday) {
            int maxDiameter = dp(h.itemView.getContext(), 46);
            h.itemView.setBackground(new CircleDrawable(
                    h.itemView.getContext().getColor(R.color.action_bg), maxDiameter));
            h.tvDayNumber.setTextColor(h.itemView.getContext().getColor(R.color.action_text));
            h.tvPersonShort.setTextColor(h.itemView.getContext().getColor(R.color.action_text));
        } else if (item.hasLog) {
            // 有日志的日期：数字改用琥珀色，和普通日期区分开。
            h.itemView.setBackground(null);
            h.tvDayNumber.setTextColor(h.itemView.getContext().getColor(R.color.log_accent));
            h.tvPersonShort.setTextColor(h.itemView.getContext().getColor(R.color.md_primary));
        } else {
            h.itemView.setBackground(null);
            h.tvDayNumber.setTextColor(h.itemView.getContext().getColor(R.color.md_on_surface));
            h.tvPersonShort.setTextColor(h.itemView.getContext().getColor(R.color.md_primary));
        }

        // 右上角小圆点：今日格子是深色底，靠圆点提示有日志。
        h.dotLog.setVisibility(item.hasLog ? View.VISIBLE : View.GONE);

        h.itemView.setOnClickListener(v -> {
            if (onDayClick != null) onDayClick.onClick(item);
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    /** 日期格子的视图引用。 */
    static class VH extends RecyclerView.ViewHolder {
        TextView tvDayNumber, tvPersonShort;
        View dotLog;
        VH(@NonNull View v) {
            super(v);
            tvDayNumber = v.findViewById(R.id.tv_day_number);
            tvPersonShort = v.findViewById(R.id.tv_person_short);
            dotLog = v.findViewById(R.id.dot_log);
        }
    }

    private static int dp(android.content.Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /**
     * 在格子中央绘制正圆。
     *
     * 直径取格子宽高的较小值，且不超过上限，行高变化时不会被拉成椭圆。
     */
    private static class CircleDrawable extends android.graphics.drawable.Drawable {

        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int maxDiameter;

        CircleDrawable(int color, int maxDiameter) {
            paint.setColor(color);
            paint.setStyle(android.graphics.Paint.Style.FILL);
            this.maxDiameter = maxDiameter;
        }

        @Override
        public void draw(@NonNull android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            int diameter = Math.min(Math.min(b.width(), b.height()), maxDiameter);
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), diameter / 2f, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}