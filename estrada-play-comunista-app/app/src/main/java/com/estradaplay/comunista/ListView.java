package com.estradaplay.comunista;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.util.AttributeSet;

/**
 * MUSIC_LIST_COMPAT_V2317
 * Thin compatibility wrapper around Android's native ListView.
 * Keeps the existing Music screen unchanged while providing the divider-color
 * helper used by that screen.
 */
final class ListView extends android.widget.ListView {
    ListView(Context context) {
        super(context);
    }

    ListView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    ListView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    ListView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    void setDividerColor(int color) {
        setDivider(new ColorDrawable(color));
    }
}
