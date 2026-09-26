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

import org.json.JSONArray;
import org.json.JSONObject;

/** Adapter daftar pesan chat (user / assistant / status tool). */
public class ChatAdapter extends BaseAdapter {

    public static final int ROLE_USER = 0;
    public static final int ROLE_BOT = 1;
    public static final int ROLE_INFO = 2;

    private static class Item {
        int role;
        String text;
        boolean typing;
        Item(int r, String t, boolean ty) { role = r; text = t; typing = ty; }
    }

    private final java.util.List<Item> items = new java.util.ArrayList<>();
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

    /** Perbarui bubble terakhir (streaming) dan kembalikan indeksnya. */
    public void updateLastBot(String text) {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).role == ROLE_BOT) { items.get(i).text = text; items.get(i).typing = false; break; }
        }
        notifyDataSetChanged();
    }

    public void clear() { items.clear(); notifyDataSetChanged(); }

    @Override public int getCount() { return items.size(); }
    @Override public Object getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) v = inflater.inflate(R.layout.item_message, parent, false);

        Item it = items.get(position);
        LinearLayout bubble = v.findViewById(R.id.bubble);
        TextView role = v.findViewById(R.id.msgRole);
        TextView text = v.findViewById(R.id.msgText);

        FrameLayoutHelper helper = new FrameLayoutHelper();
        helper.align(bubble, it.role == ROLE_USER ? Gravity.END : Gravity.START);

        if (it.role == ROLE_USER) {
            bubble.setBackgroundResource(dark ? R.drawable.bubble_user_dark_shape : R.drawable.bubble_user_shape);
            role.setTextColor(Color.parseColor(dark ? "#B8A6E8" : "#7C3AED"));
            role.setText("Kamu");
            text.setTextColor(Color.parseColor(dark ? "#ECECF4" : "#17171F"));
        } else if (it.role == ROLE_BOT) {
            bubble.setBackgroundResource(dark ? R.drawable.bubble_bot_dark_shape : R.drawable.bubble_bot_shape);
            role.setTextColor(Color.parseColor(dark ? "#9A9AAB" : "#6B6B7B"));
            role.setText(it.typing ? "ZCode • menulis…" : "ZCode");
            text.setTextColor(Color.parseColor(dark ? "#ECECF4" : "#17171F"));
        } else {
            bubble.setBackgroundResource(dark ? R.drawable.bubble_bot_dark_shape : R.drawable.bubble_bot_shape);
            role.setTextColor(Color.parseColor(dark ? "#7FB89A" : "#2E9E5B"));
            role.setText("ZCode");
            text.setTextColor(Color.parseColor(dark ? "#9A9AAB" : "#6B6B7B"));
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

    /** util kecil agar FrameLayout bisa align. */
    private static class FrameLayoutHelper {
        void align(View v, int gravity) {
            ViewGroup.LayoutParams lp0 = v.getLayoutParams();
            if (lp0 instanceof android.widget.FrameLayout.LayoutParams) {
                ((android.widget.FrameLayout.LayoutParams) lp0).gravity = gravity;
                v.setLayoutParams(lp0);
            }
        }
    }
}
