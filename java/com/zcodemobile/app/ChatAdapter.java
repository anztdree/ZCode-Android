package com.zcodemobile.app;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

/** Adapter chat: bubble user kanan, assistant markdown full-width, tool card collapsible, kartu rencana. */
public class ChatAdapter extends BaseAdapter {

    private final List<ChatItem> items;
    private final LayoutInflater inf;
    private final int fg, fgSubtle, fgSubtlest, codeBg, codeFg, ask, success, warning, destructive;
    private final MarkdownLite md;
    public PlanActionListener planListener;

    public interface PlanActionListener {
        void onApprove(ChatItem item);
        void onReject(ChatItem item);
    }

    public ChatAdapter(List<ChatItem> items, LayoutInflater inf, MarkdownLite md,
                       int fg, int fgSubtle, int fgSubtlest, int codeBg, int codeFg, int ask,
                       int success, int warning, int destructive) {
        this.items = items;
        this.inf = inf;
        this.md = md;
        this.fg = fg; this.fgSubtle = fgSubtle; this.fgSubtlest = fgSubtlest;
        this.codeBg = codeBg; this.codeFg = codeFg; this.ask = ask;
        this.success = success; this.warning = warning; this.destructive = destructive;
    }

    @Override public int getCount() { return items.size(); }
    @Override public Object getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return position; }
    @Override public int getViewTypeCount() { return 5; }
    @Override public int getItemViewType(int position) { return items.get(position).type; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ChatItem it = items.get(position);
        View v = convertView;
        switch (it.type) {
            case ChatItem.TYPE_USER: return bindUser(it, v);
            case ChatItem.TYPE_ASSISTANT: return bindAssistant(it, v);
            case ChatItem.TYPE_TOOL: return bindTool(it, v);
            case ChatItem.TYPE_PLAN: return bindPlan(it, v);
            default: return bindNote(it, v);
        }
    }

    private View bindUser(ChatItem it, View v) {
        if (v == null) v = inf.inflate(R.layout.item_msg_user, null);
        TextView txt = v.findViewById(R.id.txt);
        txt.setTextColor(fg);
        txt.setText(it.text);
        return v;
    }

    private View bindAssistant(ChatItem it, View v) {
        if (v == null) v = inf.inflate(R.layout.item_msg_assistant, null);
        TextView txt = v.findViewById(R.id.txt);
        txt.setTextColor(fg);
        txt.setMovementMethod(new LinkMovementMethod());
        txt.setText(md.render(it.text.isEmpty() ? "…" : it.text));
        return v;
    }

    private View bindNote(ChatItem it, View v) {
        if (v == null) v = inf.inflate(R.layout.item_note, null);
        TextView txt = v.findViewById(R.id.txt);
        txt.setText(it.text);
        return v;
    }

    private View bindTool(ChatItem it, View v) {
        if (v == null) v = inf.inflate(R.layout.item_tool, null);
        ImageView ico = v.findViewById(R.id.ico);
        TextView title = v.findViewById(R.id.txtTitle);
        TextView stat = v.findViewById(R.id.txtStat);
        ImageView chev = v.findViewById(R.id.chev);
        TextView out = v.findViewById(R.id.txtOut);

        ico.setImageResource(iconFor(it.toolName));
        ico.setColorFilter(fgSubtle);
        title.setText(it.toolDetail.isEmpty() ? it.toolName : it.toolDetail);
        title.setTextColor(fg);

        int color;
        String label;
        switch (it.toolStatus) {
            case ChatItem.ST_RUNNING: color = warning; label = "Berjalan…"; break;
            case ChatItem.ST_OK: color = success; label = "Selesai"; break;
            case ChatItem.ST_ERR: color = destructive; label = "Gagal"; break;
            case ChatItem.ST_DENIED: color = fgSubtlest; label = "Ditolak"; break;
            default: color = fgSubtlest; label = "Menunggu"; break;
        }
        stat.setTextColor(color);
        stat.setText(label);
        chev.setVisibility(it.text.isEmpty() ? View.GONE : View.VISIBLE);
        chev.setRotation(it.expanded ? 180f : 0f);
        chev.setColorFilter(fgSubtlest);

        if (it.expanded && !it.text.isEmpty()) {
            out.setVisibility(View.VISIBLE);
            String body = it.text.length() > 4000 ? it.text.substring(0, 4000) + "…" : it.text;
            // Diff ala ZCode PC: baris +/- berwarna utk tool Edit/Write
            if ("Edit".equals(it.toolName) || "Write".equals(it.toolName))
                out.setText(diffSpanned(body));
            else
                out.setText(body);
        } else {
            out.setVisibility(View.GONE);
        }

        View card = v.findViewById(R.id.toolCard);
        card.setOnClickListener(x -> {
            if (it.text.isEmpty()) return;
            it.expanded = !it.expanded;
            notifyDataSetChanged();
        });
        return v;
    }

    private View bindPlan(ChatItem it, View v) {
        if (v == null) v = inf.inflate(R.layout.item_plan, null);
        TextView plan = v.findViewById(R.id.txtPlan);
        plan.setTextColor(fg);
        plan.setText(md.render(it.text));
        View buttons = v.findViewById(R.id.planButtons);
        if (it.planResolved) {
            buttons.setVisibility(View.GONE);
        } else {
            buttons.setVisibility(View.VISIBLE);
            TextView approve = v.findViewById(R.id.btnApprove);
            TextView reject = v.findViewById(R.id.btnReject);
            approve.setOnClickListener(x -> { if (planListener != null) planListener.onApprove(it); });
            reject.setOnClickListener(x -> { if (planListener != null) planListener.onReject(it); });
        }
        return v;
    }

    /** Spannable diff: baris mulai "+" hijau, "-" merah, sisanya netral. */
    private CharSequence diffSpanned(String body) {
        try {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            int i = 0;
            while (i < body.length()) {
                int nl = body.indexOf('\n', i);
                if (nl < 0) nl = body.length();
                String line = body.substring(i, nl);
                int color = codeFg;
                if (line.startsWith("+")) color = success;
                else if (line.startsWith("-")) color = destructive;
                int start = sb.length();
                sb.append(line);
                if (nl < body.length()) sb.append('\n');
                sb.setSpan(new android.text.style.ForegroundColorSpan(color),
                        start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = nl + 1;
            }
            return sb;
        } catch (Exception e) {
            return body;
        }
    }

    public static int iconFor(String tool) {
        switch (tool) {
            case "Read": return R.drawable.ic_file_text;
            case "Write": return R.drawable.ic_pen;
            case "Edit": return R.drawable.ic_pen;
            case "Delete": return R.drawable.ic_trash;
            case "Glob": return R.drawable.ic_folder;
            case "Grep": return R.drawable.ic_search;
            case "WebFetch": return R.drawable.ic_globe;
            case "WebSearch": return R.drawable.ic_search;
            case "TodoWrite": return R.drawable.ic_list_checks;
            case "TodoRead": return R.drawable.ic_list_todo;
            case "AskUserQuestion": return R.drawable.ic_help;
            case "EnterPlanMode": return R.drawable.ic_target;
            case "ExitPlanMode": return R.drawable.ic_target;
            default: return R.drawable.ic_terminal;
        }
    }
}
