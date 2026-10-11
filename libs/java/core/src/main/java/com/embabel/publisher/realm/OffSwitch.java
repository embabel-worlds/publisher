package com.embabel.publisher.realm;

import java.util.Locale;
import java.util.Set;

/**
 * The one switch every publisher library honours (protocol §2.2). The environment variable wins
 * over any configuration: an operator can always turn a publisher off without a build. It turns
 * publication off; it can never turn on a publisher its configuration turned off.
 */
public final class OffSwitch {

    public static final String VARIABLE = "EMBABEL_REALM_ENABLED";

    private static final Set<String> FALSE = Set.of("false", "off", "no", "0");

    private OffSwitch() {
    }

    /**
     * @param variable   the value of {@code EMBABEL_REALM_ENABLED}, or {@code null} when unset
     * @param configured what the stack's own configuration says
     */
    public static boolean enabled(String variable, boolean configured) {
        if (variable != null && FALSE.contains(variable.trim().toLowerCase(Locale.ROOT))) {
            return false;
        }
        return configured;
    }

    /** For a stack with no configuration of its own: the process environment alone. */
    public static boolean enabledByEnvironment() {
        return enabled(System.getenv(VARIABLE), true);
    }
}
