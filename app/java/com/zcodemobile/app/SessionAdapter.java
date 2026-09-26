package com.zcodemobile.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.TextView;

import java.util.List;

/** Adapter daftar sesi di sidebar (drawer) — ala task list ZCode. */
public class SessionAdapter extends BaseAdapter {

    public final List<SessionStore.Meta> items;
    private final LayoutInflater inf;
    private final int fg, fgSubtlest;
    public final String currentId;
    public SessionListener listener;

    public interface SessionListener {
        void onOpen(SessionStore.Meta m);
        void onDelete(SessionStore.Meta m);
    }

    public SessionAdapter(List<SessionStore.Meta> items, LayoutInflater inf,
                          int fg, int fgSubtlest, String currentId) {
        this.items = items;
        this.inf = inf;
        this.fg = fg; this.fgSubtlest = fgSubtlest;
        this.currentId = currentId;
    }

    @Override public int getCount() { return items.size(); }
    @Override public Object getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) convertView = inf.inflate(R.layout.item_session, null);
        SessionStore.Meta m = items.get(position);
        TextView title = convertView.findViewById(R.id.txtTitle);
        TextView time = convertView.findViewById(R.id.txtTime);
        TextView active = convertView.findViewById(R.id.txtActive);
        ImageButton del = convertView.findViewById(R.id.btnDel);

        title.setTextColor(m.id.equals(currentId) ? fg : fg);
        time.setTextColor(fgSubtlest);
        title.setText(m.title);
        time.setText(relTime(m.updated));
        active.setVisibility(m.id.equals(currentId) ? View.VISIBLE : View.GONE);
        del.setColorFilter(fgSubtlest);

        convertView.setOnClickListener(v -> { if (listener != null) listener.onOpen(m); });
        del.setOnClickListener(v -> { if (listener != null) listener.onDelete(m); });
        return convertView;
    }

    private String relTime(long t) {
        long d = System.currentTimeMillis() - t;
        if (d < 60_000) return "baru saja";
        if (d < 3_600_000) return (d / 60_000) + " mnt lalu";
        if (d < 86_400_000) return (d / 3_600_000) + " jam lalu";
        return (d / 86_400_000) + " hari lalu";
    }
}
