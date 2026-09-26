package com.zcodemobile.app;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Adapter penjelajah berkas: folder dulu, ikon diwarnai per ekstensi ala material-icon-theme. */
public class FilesAdapter extends BaseAdapter {

    public final List<File> items = new ArrayList<>();
    private final File root;
    private final LayoutInflater inf;
    private final int fg, fgSubtle, fgSubtlest;

    public FilesAdapter(File root, LayoutInflater inf, int fg, int fgSubtle, int fgSubtlest) {
        this.root = root;
        this.inf = inf;
        this.fg = fg; this.fgSubtle = fgSubtle; this.fgSubtlest = fgSubtlest;
    }

    public void reload(String subDir) {
        items.clear();
        File dir = (subDir == null || subDir.isEmpty()) ? root : new File(root, subDir);
        File[] fs = dir.listFiles();
        if (fs != null) {
            List<File> dirs = new ArrayList<>(), files = new ArrayList<>();
            for (File f : fs) {
                if (f.getName().startsWith(".")) continue;
                (f.isDirectory() ? dirs : files).add(f);
            }
            Collections.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            Collections.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            items.addAll(dirs);
            items.addAll(files);
        }
        notifyDataSetChanged();
    }

    @Override public int getCount() { return items.size(); }
    @Override public Object getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) convertView = inf.inflate(R.layout.item_file, null);
        File f = items.get(position);
        ImageView ico = convertView.findViewById(R.id.ico);
        TextView name = convertView.findViewById(R.id.txtName);
        TextView sub = convertView.findViewById(R.id.txtSub);
        ImageView chev = convertView.findViewById(R.id.chev);

        name.setTextColor(fg);
        sub.setTextColor(fgSubtlest);
        chev.setVisibility(View.VISIBLE);
        chev.setRotation(f.isDirectory() ? -90f : 0f);
        chev.setImageResource(f.isDirectory() ? R.drawable.ic_chevron_down : R.drawable.ic_file_text);
        chev.setColorFilter(f.isDirectory() ? fgSubtle : fgSubtlest);

        if (f.isDirectory()) {
            name.setText(f.getName());
            sub.setText("folder");
            ico.setImageResource(R.drawable.ic_folder);
            ico.setColorFilter(0xFFE8B04B);
        } else {
            name.setText(f.getName());
            sub.setText(formatSize(f.length()) + " · " + fmtDate(f.lastModified()));
            int color = colorForExt(f.getName());
            ico.setImageResource(R.drawable.ic_file_text);
            ico.setColorFilter(color);
        }
        return convertView;
    }

    public static int colorForExt(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".xml")) return 0xFFE44D26;
        if (n.endsWith(".css")) return 0xFF663399;
        if (n.endsWith(".js") || n.endsWith(".mjs")) return 0xFFF7DF1E;
        if (n.endsWith(".ts") || n.endsWith(".tsx")) return 0xFF3178C6;
        if (n.endsWith(".java") || n.endsWith(".kt")) return 0xFFB07219;
        if (n.endsWith(".py")) return 0xFF3572A5;
        if (n.endsWith(".json")) return 0xFFCBBD5B;
        if (n.endsWith(".md")) return 0xFF8DB6FF;
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif") || n.endsWith(".webp") || n.endsWith(".svg"))
            return 0xFFA074C4;
        return fgDefault;
    }

    private static final int fgDefault = 0xFF9E9E9E;

    private String formatSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024f);
        return String.format(Locale.US, "%.1f MB", b / 1024f / 1024f);
    }

    private String fmtDate(long t) {
        return new SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(new Date(t));
    }
}
