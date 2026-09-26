package com.zcodemobile.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Adapter penjelajah berkas (folder dulu, lalu berkas). */
public class FilesAdapter extends BaseAdapter {

    public interface Listener {
        void onOpenFile(File f);
        void onOpenFolder(File f);
        void onMore(File f);
    }

    private final LayoutInflater inflater;
    private final List<File> entries = new ArrayList<>();
    private Listener listener;

    public FilesAdapter(LayoutInflater li) { inflater = li; }

    public void setListener(Listener l) { listener = l; }

    public void load(File dir) {
        entries.clear();
        File[] items = dir.listFiles();
        if (items != null) {
            Arrays.sort(items, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            entries.addAll(Arrays.asList(items));
        }
        notifyDataSetChanged();
    }

    @Override public int getCount() { return entries.size(); }
    @Override public Object getItem(int position) { return entries.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) v = inflater.inflate(R.layout.item_file, parent, false);
        final File f = entries.get(position);
        TextView icon = v.findViewById(R.id.fileIcon);
        TextView name = v.findViewById(R.id.fileName);
        TextView more = v.findViewById(R.id.fileMore);
        icon.setText(f.isDirectory() ? "📁" : iconFor(f.getName()));
        name.setText(f.getName());
        v.setOnClickListener(x -> {
            if (listener == null) return;
            if (f.isDirectory()) listener.onOpenFolder(f); else listener.onOpenFile(f);
        });
        more.setOnClickListener(x -> { if (listener != null) listener.onMore(f); });
        return v;
    }

    private String iconFor(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html")) return "🌐";
        if (n.endsWith(".css")) return "🎨";
        if (n.endsWith(".js") || n.endsWith(".ts") || n.endsWith(".java") || n.endsWith(".kt")) return "📜";
        if (n.endsWith(".json")) return "🔧";
        if (n.endsWith(".md")) return "📝";
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".gif") || n.endsWith(".webp")) return "🖼️";
        return "📄";
    }
}
