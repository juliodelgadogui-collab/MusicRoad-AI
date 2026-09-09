package com.estradaplay.comunista;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.ComponentActivity;

/**
 * Compatibility entry point only.
 * The legacy automotive dashboard was retired in Estrada Play 5.0.4.
 */
public final class AutomotiveActivity extends ComponentActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent next = new Intent(this, RoadEntryActivity.class);
        next.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(next);
        finish();
    }
}
