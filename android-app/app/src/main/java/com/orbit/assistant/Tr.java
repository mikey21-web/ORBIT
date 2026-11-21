package com.orbit.assistant;

import android.content.Context;

/**
 * Text helper for the app's own strings.
 *
 * ORBIT is English-only; strings are provided directly in English, so this
 * helper is a pass-through and view translation is a no-op. The API is kept
 * so call sites remain unchanged.
 */
final class Tr {

    private Tr() {
    }

    static void attach(Context context) {
    }

    /** ORBIT is English-only: return the string unchanged. */
    static String t(String text) {
        return text == null ? "" : text;
    }

    /** No-op: all views are already in English. */
    static void apply(android.view.View root) {
    }
}
