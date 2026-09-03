package com.estradaplay.comunista;

/** REINSTALL_AUTH_FIX_V231: separates remembered UI state from secure authentication state. */
final class ReinstallSessionPolicy {
    private ReinstallSessionPolicy() {}

    static boolean shouldRequireLogin(boolean accountRemembered, boolean online, boolean secureSessionAvailable) {
        return accountRemembered && online && !secureSessionAvailable;
    }
}
