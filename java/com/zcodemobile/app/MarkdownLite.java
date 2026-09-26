package com.zcodemobile.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.TypefaceSpan;

/** Render markdown ala ZCode: blok kode SELALU gelap (#171717) seperti desktop. */
public class MarkdownLite {

    private static final int CODE_BG = Color.parseColor("#171717");
    private static final int CODE_FG = Color.parseColor("#FAFAFA");

    public static CharSequence render(String raw) {
        if (raw == null) return "";
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = raw.split("\n", -1);
        boolean inCode = false;
        StringBuilder codeBuf = new StringBuilder();

        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("```")) {
                if (inCode) {
                    int start = out.length();
                    out.append(codeBuf);
                    out.setSpan(new TypefaceSpan("monospace"), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(CODE_BG), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new ForegroundColorSpan(CODE_FG), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new LeadingMarginSpan.Standard(8, 8), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.append("\n");
                    codeBuf.setLength(0);
                }
                inCode = !inCode;
                continue;
            }
            if (inCode) { codeBuf.append(line).append('\n'); continue; }

            if (t.startsWith("### ") || t.startsWith("## ") || t.startsWith("# ")) {
                int s = out.length();
                out.append(t.substring(t.indexOf(' ') + 1)).append('\n');
                out.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), s, out.length() - 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new android.text.style.RelativeSizeSpan(1.1f), s, out.length() - 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            if (t.startsWith("- ") || t.startsWith("* ")) {
                int s = out.length();
                out.append("• ").append(t.substring(2)).append('\n');
                out.setSpan(new LeadingMarginSpan.Standard(10, 14), s, out.length() - 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            if (t.startsWith("`") && t.endsWith("`") && t.length() > 2) {
                int s = out.length();
                out.append(t.substring(1, t.length() - 1)).append('\n');
                out.setSpan(new TypefaceSpan("monospace"), s, out.length() - 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(0x1A171717), s, out.length() - 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            out.append(line).append('\n');
        }
        if (inCode && codeBuf.length() > 0) {
            int start = out.length();
            out.append(codeBuf);
            out.setSpan(new TypefaceSpan("monospace"), start, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new BackgroundColorSpan(CODE_BG), start, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new ForegroundColorSpan(CODE_FG), start, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }
}
