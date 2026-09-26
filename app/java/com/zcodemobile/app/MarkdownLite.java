package com.zcodemobile.app;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.view.View;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown ringan ala ZCode (GitHub-flavored):
 * blok kode ``` dengan label bahasa + tombol Salin, heading, bullet/numbered list,
 * blockquote, garis, bold, italic, inline code, dan link.
 */
public class MarkdownLite {

    public interface CodeCopyListener { void onCopyCode(String code); }

    private final int fg, fgSubtle, fgSubtlest, codeBg, codeFg, ask;
    private final CodeCopyListener copyListener;

    public MarkdownLite(int fg, int fgSubtle, int fgSubtlest, int codeBg, int codeFg, int ask,
                        CodeCopyListener copyListener) {
        this.fg = fg; this.fgSubtle = fgSubtle; this.fgSubtlest = fgSubtlest;
        this.codeBg = codeBg; this.codeFg = codeFg; this.ask = ask;
        this.copyListener = copyListener;
    }

    public SpannableStringBuilder render(String mdText) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (mdText == null) return out;
        String[] lines = mdText.replace("\r\n", "\n").split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String t = line.trim();

            if (t.startsWith("```")) {
                String lang = t.length() > 3 ? t.substring(3).trim() : "";
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n');
                    i++;
                }
                i++; // lewati penutup ```
                appendCodeBlock(out, lang, code.toString());
            } else if (t.startsWith("### ")) {
                ensureNL(out);
                out.append(inline(t.substring(4), fg, true, 0));
            } else if (t.startsWith("## ")) {
                ensureNL(out);
                out.append(inline(t.substring(3), fg, true, 1));
            } else if (t.startsWith("# ")) {
                ensureNL(out);
                out.append(inline(t.substring(2), fg, true, 2));
            } else if (t.matches("(-{3,}|_{3,}|\\*{3,})")) {
                ensureNL(out);
                out.append("──────────");
            } else if (t.startsWith("> ")) {
                ensureNL(out);
                CharSequence q = inline(t.substring(2), fgSubtle, false, -1);
                SpannableStringBuilder li = new SpannableStringBuilder();
                li.append("▎ ");
                li.append(q);
                li.setSpan(new LeadingMarginSpan.Standard(0, 12), 0, li.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append(li);
            } else if (t.startsWith("- ") || t.startsWith("* ")) {
                ensureNL(out);
                SpannableStringBuilder li = new SpannableStringBuilder();
                li.append("•  ");
                li.append(inline(t.substring(2), fg, false, -1));
                li.setSpan(new LeadingMarginSpan.Standard(0, 16), 0, li.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append(li);
            } else if (t.matches("\\d+\\. .*")) {
                ensureNL(out);
                int dot = t.indexOf(". ");
                SpannableStringBuilder li = new SpannableStringBuilder();
                li.append(t.substring(0, dot + 1)).append("  ");
                li.append(inline(t.substring(dot + 2), fg, false, -1));
                li.setSpan(new LeadingMarginSpan.Standard(0, 22), 0, li.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append(li);
            } else if (t.isEmpty()) {
                if (out.length() > 0) out.append("\n");
            } else {
                ensureNL(out);
                out.append(inline(t, fg, false, -1));
            }
            i++;
        }
        return out;
    }

    private void ensureNL(SpannableStringBuilder out) {
        int n = out.length();
        if (n > 0 && out.charAt(n - 1) != '\n') out.append("\n");
    }

    /** Blok kode ala ZCode: header "‹lang› · Salin" + isi monospace berlatar. */
    private void appendCodeBlock(SpannableStringBuilder out, String lang, String code) {
        ensureNL(out);
        String head = (lang.isEmpty() ? "kode" : lang) + " · Salin";
        int start = out.length();
        out.append(head);
        int end = out.length();
        out.setSpan(new RelativeSizeSpan(0.78f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ColorSpan(ask), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ClickableSpan() {
            @Override public void onClick(View widget) {
                if (copyListener != null) copyListener.onCopyCode(code.toString());
            }
            @Override public void updateDrawState(TextPaint ds) {
                ds.setColor(ask);
                ds.setUnderlineText(true);
            }
        }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append("\n");

        String body = code.endsWith("\n") ? code.substring(0, code.length() - 1) : code;
        if (body.length() > 4000) body = body.substring(0, 4000) + "\n… (dipotong)";
        int bs = out.length();
        out.append(body);
        int be = out.length();
        out.setSpan(new MonoSpan(), bs, be, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new BackgroundColorSpan(codeBg), bs, be, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ColorSpan(codeFg), bs, be, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append("\n");
    }

    /** Parser inline: **bold**, *italic*, `code`, ~~strike~~, [teks](url). */
    private SpannableStringBuilder inline(String s, int color, boolean forceBold, int heading) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(s);
        sb = decorate(sb);
        int len = sb.length();
        if (len == 0) return sb;
        sb.setSpan(new ColorSpan(color), 0, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (forceBold || heading >= 0) sb.setSpan(new StyleSpan(Typeface.BOLD), 0, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (heading >= 0) {
            float scale = heading == 2 ? 1.45f : (heading == 1 ? 1.28f : 1.14f);
            sb.setSpan(new RelativeSizeSpan(scale), 0, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    /** Ganti markup inline menjadi span; proses kemunculan paling awal berulang-ulang. */
    private SpannableStringBuilder decorate(SpannableStringBuilder sb) {
        int guard = 0;
        boolean changed = true;
        while (changed && guard++ < 300) {
            changed = false;
            String s = sb.toString();
            int idx;

            idx = s.indexOf('`');
            if (idx >= 0) {
                int close = s.indexOf('`', idx + 1);
                if (close > idx) {
                    sb.replace(close, close + 1, "");
                    sb.replace(idx, idx + 1, "");
                    sb.setSpan(new MonoSpan(), idx, close - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new BackgroundColorSpan(inlineBg()), idx, close - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new RelativeSizeSpan(0.92f), idx, close - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    changed = true;
                    continue;
                } else {
                    sb.replace(idx, idx + 1, "");
                    changed = true;
                    continue;
                }
            }

            idx = s.indexOf("**");
            if (idx >= 0) {
                int close = s.indexOf("**", idx + 2);
                if (close > idx) {
                    sb.replace(close, close + 2, "");
                    sb.replace(idx, idx + 2, "");
                    sb.setSpan(new StyleSpan(Typeface.BOLD), idx, close - 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {
                    sb.replace(idx, idx + 2, "");
                }
                changed = true;
                continue;
            }

            idx = s.indexOf("~~");
            if (idx >= 0) {
                int close = s.indexOf("~~", idx + 2);
                if (close > idx) {
                    sb.replace(close, close + 2, "");
                    sb.replace(idx, idx + 2, "");
                    sb.setSpan(new StrikethroughSpan(), idx, close - 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    changed = true;
                    continue;
                }
            }

            int star = -1;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '*'
                        && (i == 0 || s.charAt(i - 1) != '*')
                        && (i + 1 >= s.length() || s.charAt(i + 1) != '*')) {
                    star = i;
                    break;
                }
            }
            if (star >= 0) {
                int close = s.indexOf('*', star + 1);
                if (close > star) {
                    sb.replace(close, close + 1, "");
                    sb.replace(star, star + 1, "");
                    sb.setSpan(new StyleSpan(Typeface.ITALIC), star, close - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    changed = true;
                    continue;
                }
            }

            Matcher m = Pattern.compile("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)").matcher(s);
            if (m.find()) {
                int a = m.start();
                String text = m.group(1);
                String url = m.group(2);
                sb.replace(a, m.end(), text);
                sb.setSpan(new LinkSpan(url, ask), a, a + text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                changed = true;
                continue;
            }
        }
        return sb;
    }

    private int inlineBg() {
        int r = Color.red(fg), g = Color.green(fg), b = Color.blue(fg);
        return Color.argb(0x1A, r, g, b);
    }

    /* ====== Span kustom ====== */

    static class ColorSpan extends MetricAffectingSpan {
        private final int color;
        ColorSpan(int c) { color = c; }
        @Override public void updateMeasureState(TextPaint tp) { tp.setColor(color); }
        @Override public void updateDrawState(TextPaint tp) { tp.setColor(color); }
    }

    static class MonoSpan extends MetricAffectingSpan {
        @Override public void updateMeasureState(TextPaint tp) { apply(tp); }
        @Override public void updateDrawState(TextPaint tp) { apply(tp); }
        private void apply(TextPaint tp) { tp.setTypeface(Typeface.MONOSPACE); }
    }

    static class LinkSpan extends ClickableSpan {
        private final String url;
        private final int color;
        LinkSpan(String u, int c) { url = u; color = c; }
        @Override public void onClick(View widget) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                widget.getContext().startActivity(i);
            } catch (Exception ignore) { }
        }
        @Override public void updateDrawState(TextPaint ds) {
            ds.setColor(color);
            ds.setUnderlineText(true);
        }
    }
}
