package com.zcodemobile.app;

import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** Adapter todo — format ZCode: status pending/in_progress/completed + priority. */
public class TodoAdapter extends BaseAdapter {

    private final JSONArray items;
    private final LayoutInflater inf;
    private final int fg, fgSubtle, fgSubtlest, success, warning, destructive;

    public TodoAdapter(JSONArray items, LayoutInflater inf,
                       int fg, int fgSubtle, int fgSubtlest, int success, int warning, int destructive) {
        this.items = items;
        this.inf = inf;
        this.fg = fg; this.fgSubtle = fgSubtle; this.fgSubtlest = fgSubtlest;
        this.success = success; this.warning = warning; this.destructive = destructive;
    }

    @Override public int getCount() { return items.length(); }
    @Override public Object getItem(int position) { return items.opt(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) convertView = inf.inflate(R.layout.item_todo, null);
        JSONObject o = items.optJSONObject(position);
        if (o == null) return convertView;
        String status = o.optString("status", "pending");
        String priority = o.optString("priority", "medium");
        String content = o.optString("content", o.optString("title", ""));

        ImageView chk = convertView.findViewById(R.id.chk);
        TextView txt = convertView.findViewById(R.id.txtContent);
        TextView pri = convertView.findViewById(R.id.txtPriority);

        int color;
        switch (status) {
            case "completed":
                chk.setImageResource(R.drawable.ic_check);
                chk.setColorFilter(success);
                color = fgSubtle;
                txt.setPaintFlags(txt.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                break;
            case "in_progress":
                chk.setImageResource(R.drawable.ic_target);
                chk.setColorFilter(warning);
                color = fg;
                txt.setPaintFlags(txt.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
                break;
            default:
                chk.setImageResource(R.drawable.ic_circle);
                chk.setColorFilter(fgSubtlest);
                color = fg;
                txt.setPaintFlags(txt.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
                break;
        }
        txt.setTextColor(color);
        txt.setText(content);
        pri.setText("high".equals(priority) ? "tinggi" : ("low".equals(priority) ? "rendah" : "sedang"));
        pri.setTextColor("high".equals(priority) ? destructive : ("low".equals(priority) ? fgSubtlest : fgSubtle));
        return convertView;
    }
}
