package com.zcodemobile.app;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.TypefaceSpan;

/** Render markdown ringan: blok kode ``` + heading + bullet. */
public class MarkdownLite {

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
                    // tutup blok kode
                    int start = out.length();
                    out.append(codeBuf);
                    out.setSpan(new TypefaceSpan("monospace"), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(0x22000000), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new LeadingMarginSpan.Standard(12, 12), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.append("\n");
                    codeBuf.setLength(0);
                }
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                codeBuf.append(line).append('\n');
                continue;
            }
            if (t.startsWith("### ") || t.startsWith("## ") || t.startsWith("# ")) {
                int s = out.length();
                out.append(t.substring(t.indexOf(' ') + 1)).append('\n');
                out.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), s, out.length() - 1,
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
            out.setSpan(new BackgroundColorSpan(0x22000000), start, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }
}
