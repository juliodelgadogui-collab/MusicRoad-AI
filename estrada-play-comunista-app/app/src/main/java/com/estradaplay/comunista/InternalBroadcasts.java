package com.estradaplay.comunista;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;

/** Registers app-internal broadcasts consistently on Android 7 through Android 16+. */
final class InternalBroadcasts {
    private InternalBroadcasts() {}

    static Intent register(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        return ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }
}
