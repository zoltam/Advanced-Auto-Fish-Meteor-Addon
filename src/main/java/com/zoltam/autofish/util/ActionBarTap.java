package com.zoltam.autofish.util;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public final class ActionBarTap {
    private static final AtomicReference<String> LAST = new AtomicReference<>("");
    private ActionBarTap() {}

    public static void set(String s) {
        if (s != null) LAST.set(s);
    }

    public static String get() { return LAST.get(); }

    public static boolean contains(String needle) {
        String s = LAST.get();
        return s != null && s.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
