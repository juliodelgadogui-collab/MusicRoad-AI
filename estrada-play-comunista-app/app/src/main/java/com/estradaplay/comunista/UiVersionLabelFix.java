package com.estradaplay.comunista;

import android.app.Application;

/**
 * Legacy compatibility stub.
 *
 * Estrada Play 5.0.4 no longer renders the old EPC/Central Automotiva UI, so there is no
 * user-facing version label to rewrite at runtime. The method remains temporarily to keep
 * older initialization code binary/source compatible while the legacy layer is retired.
 */
final class UiVersionLabelFix {
    private UiVersionLabelFix() {}

    static void register(Application app) {
        // Intentionally empty. Premium screens render BuildConfig.VERSION_NAME directly.
    }
}
