package com.estradaplay.comunista;

/** Pure policy for deciding when the server has explicitly revoked the local session. */
final class SessionValidityPolicyV300 {
    private SessionValidityPolicyV300() {}

    static boolean isExplicitRevocation(int httpCode) {
        return httpCode == 401 || httpCode == 403;
    }
}
