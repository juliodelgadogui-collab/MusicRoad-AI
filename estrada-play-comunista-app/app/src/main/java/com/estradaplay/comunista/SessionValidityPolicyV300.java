package com.estradaplay.comunista;

/** Pure policy for deciding whether a background validation still belongs to the live session. */
final class SessionValidityPolicyV300 {
    private SessionValidityPolicyV300() {}

    static boolean isExplicitRevocation(int httpCode) {
        return httpCode == 401 || httpCode == 403;
    }

    // SESSION_ACCOUNT_SWITCH_GUARD_V300: a late response may only touch the exact
    // account + credential generation that existed when its request started.
    static boolean sameValidationSubject(String accountAtStart, String accountNow,
                                         String sessionAtStart, String sessionNow) {
        String expectedAccount = normalize(accountAtStart);
        String currentAccount = normalize(accountNow);
        String expectedSession = normalize(sessionAtStart);
        String currentSession = normalize(sessionNow);
        return !expectedAccount.isEmpty()
                && !expectedSession.isEmpty()
                && expectedAccount.equals(currentAccount)
                && expectedSession.equals(currentSession);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
