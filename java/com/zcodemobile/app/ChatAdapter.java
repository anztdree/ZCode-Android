package com.zcodemobile.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter chat ala ZCode Desktop: bubble user (hitam pekat), bubble bot (kartu),
 * TOOL CARD dengan ikon+status (berjalan/berhasil/gagal), dan baris info.
 */
public class ChatAdapter extends BaseAdapter {

    public static final int ROLE_USER = 0;
    public static final int ROLE_BOT = 1;
    public static final int ROLE_INFO = 2;
    public static final int ROLE_TOOL = 3;

    private static class Item {
        int role;
        String text;
        String detail;
        boolean typing;
        boolean ok; // status tool
        boolean running;
        Item(int r, String t, boolean ty) { role = r; text = t; typing = ty; }
    }

    private final List<Item> items = new ArrayList<>();
    private final LayoutInflater inflater;
    private final boolean dark;

    public ChatAdapter(Context ctx, boolean darkTheme) {
        inflater = LayoutInflater.from(ctx);
        dark = darkTheme;
    }

    public void addUser(String text) { items.add(new Item(ROLE_USER, text, false)); notifyDataSetChanged(); }
    public void addBot(String text) { items.add(new Item(ROLE_BOT, text, false)); notifyDataSetChanged(); }
    public void addInfo(String text) { items.add(new Item(ROLE_INFO, text, false)); notifyDataSetChanged(); }
    public void addTyping() { items.add(new Item(ROLE_BOT, "…", true)); notifyDataSetChanged(); }

    /** Tambah kartu tool yang sedang berjalan; kembalikan posisinya. */
    public int addTool(String name, String argsPreview) {
        Item it = new Item(ROLE_TOOL, "", false);
        it.detail = argsPreview == null ? "" : argsPreview;
        it.running = true;
        items.add(it);
        notifyDataSetChanged();
        return items.size() - 1;
    }

    /** Perbarui kartu tool dengan hasil. */
    public void updateTool(int pos, String resultPreview, boolean ok) {
        if (pos < 0 || pos >= items.size()) return;
        Item it = items.get(pos);
        it.running = false;
        it.ok = ok;
        it.detail = resultPreview == null ? "" : resultPreview;
        notifyDataSetChanged();
    }

    public void updateLastBot(String text) {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).role == ROLE_BOT) { items.get(i).text = text; items.get(i).typing = false; break; }
        }
        notifyDataSetChanged();
    }

    public String textAt(int pos) {
        if (pos < 0 || pos >= items.size()) return null;
        return items.get(pos).text;
    }

    public void clear() { items.clear(); notifyDataSetChanged(); }

    @Override public int getCount() { return items.size(); }
    @Override public Object getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return position; }
    @Override public int getViewTypeCount() { return 4; }
    @Override public int getItemViewType(int position) { return items.get(position).role; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Item it = items.get(position);

        if (it.role == ROLE_TOOL) {
            View v = convertView;
            if (v == null || !(v.getTag() instanceof Integer) || (Integer) v.getTag() != ROLE_TOOL) {
                v = inflater.inflate(R.layout.item_tool, parent, false);
            }
            v.setTag(ROLE_TOOL);
            v.setBackgroundResource(dark ? R.drawable.tool_card_dark_shape : R.drawable.tool_card_shape);

            TextView icon = v.findViewById(R.id.toolIcon);
            TextView name = v.findViewById(R.id.toolName);
            TextView status = v.findViewById(R.id.toolStatus);
            TextView detail = v.findViewById(R.id.toolDetail);

            icon.setText(iconFor(it.text));
            name.setText(it.text);
            name.setTextColor(color(dark ? "#FAFAFA" : "#0A0A0A"));

            if (it.running) {
                status.setText("● " + "berjalan…");
                status.setTextColor(color(dark ? "#FF8A30" : "#E07B00"));
            } else if (it.ok) {
                status.setText("✓ selesai");
                status.setTextColor(color(dark ? "#46BF72" : "#1E8A3E"));
            } else {
                status.setText("✕ gagal");
                status.setTextColor(color(dark ? "#FF6B6B" : "#E03131"));
            }
            detail.setText(it.detail);
            detail.setTextColor(color(dark ? "#A3A3A3" : "#737373"));
            return v;
        }

        View v = convertView;
        if (v == null || !(v.getTag() instanceof Integer) || (Integer) v.getTag() == ROLE_TOOL) {
            v = inflater.inflate(R.layout.item_message, parent, false);
        }
        v.setTag(it.role);

        LinearLayout bubble = v.findViewById(R.id.bubble);
        TextView role = v.findViewById(R.id.msgRole);
        TextView text = v.findViewById(R.id.msgText);

        ViewGroup.LayoutParams lp0 = bubble.getLayoutParams();
        if (lp0 instanceof android.widget.FrameLayout.LayoutParams) {
            ((android.widget.FrameLayout.LayoutParams) lp0).gravity =
                    it.role == ROLE_USER ? Gravity.END : Gravity.START;
            bubble.setLayoutParams(lp0);
        }

        if (it.role == ROLE_USER) {
            bubble.setBackgroundResource(dark ? R.drawable.bubble_user_dark_shape : R.drawable.bubble_user_shape);
            role.setText("Kamu");
            role.setTextColor(color(dark ? "#0A0A0A99" : "#FFFFFF99"));
            text.setTextColor(color(dark ? "#0A0A0A" : "#FFFFFF"));
        } else if (it.role == ROLE_BOT) {
            bubble.setBackgroundResource(dark ? R.drawable.bubble_bot_dark_shape : R.drawable.bubble_bot_shape);
            role.setText(it.typing ? "ZCode • menulis…" : "ZCode");
            role.setTextColor(color(dark ? "#A3A3A3" : "#737373"));
            text.setTextColor(color(dark ? "#FAFAFA" : "#0A0A0A"));
        } else {
            bubble.setBackgroundResource(dark ? R.drawable.bubble_bot_dark_shape : R.drawable.bubble_bot_shape);
            role.setText("ZCode");
            role.setTextColor(color(dark ? "#46BF72" : "#1E8A3E"));
            text.setTextColor(color(dark ? "#A3A3A3" : "#737373"));
            text.setTextSize(13f);
        }

        if (it.role == ROLE_INFO) {
            text.setText(it.text);
            text.setTypeface(Typeface.MONOSPACE, Typeface.ITALIC);
        } else {
            text.setTypeface(Typeface.DEFAULT);
            text.setText(MarkdownLite.render(it.text));
        }
        return v;
    }

    private String iconFor(String tool) {
        if (tool == null) return "⚙️";
        switch (tool) {
            case "write_file": return "📝";
            case "read_file": return "📖";
            case "edit_file": return "✏️";
            case "list_files": return "📂";
            case "grep": return "🔍";
            case "delete_path": return "🗑️";
            case "todo_write": return "✅";
            case "web_fetch": return "🌐";
            default: return "⚙️";
        }
    }

    private int color(String hex) { return Color.parseColor(hex); }
}
