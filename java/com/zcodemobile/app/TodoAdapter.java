package com.zcodemobile.app;

import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** Adapter daftar tugas. */
public class TodoAdapter extends BaseAdapter {

    public interface Listener {
        void onToggle(int pos);
        void onDelete(int pos);
    }

    private final LayoutInflater inflater;
    private JSONArray data = new JSONArray();
    private Listener listener;

    public TodoAdapter(LayoutInflater li) { inflater = li; }

    public void setListener(Listener l) { listener = l; }

    public void setData(JSONArray arr) { data = arr == null ? new JSONArray() : arr; notifyDataSetChanged(); }

    @Override public int getCount() { return data.length(); }
    @Override public Object getItem(int position) { return data.optJSONObject(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) v = inflater.inflate(R.layout.item_todo, parent, false);
        JSONObject o = data.optJSONObject(position);
        if (o == null) return v;
        boolean done = o.optBoolean("done");
        CheckBox check = v.findViewById(R.id.todoCheck);
        TextView text = v.findViewById(R.id.todoText);
        TextView del = v.findViewById(R.id.todoDel);
        check.setOnCheckedChangeListener(null);
        check.setChecked(done);
        text.setText(o.optString("title", "(tanpa judul)"));
        text.setPaintFlags(text.getPaintFlags() & (done ? ~0 : 0));
        if (done) text.setPaintFlags(text.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        else text.setPaintFlags(text.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        check.setOnCheckedChangeListener((b, w) -> { if (listener != null) listener.onToggle(position); });
        del.setOnClickListener(x -> { if (listener != null) listener.onDelete(position); });
        return v;
    }
}
