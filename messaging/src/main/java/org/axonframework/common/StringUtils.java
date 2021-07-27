package org.axonframework.common;

import java.util.Objects;

/**
 * Utility methods for when dealing with {@link String}s.
 *
 * @author Steven van Beelen
 * @since 4.5
 */
public abstract class StringUtils {

    private static final String EMPTY_STRING = "";

    private StringUtils() {
        // Utility class
    }

    /**
     * Validate whether the given {@link String} {@code s} is not {@code null} and not empty (where empty is defined as
     * {@code ""}.
     *
     * @param s the {@link String} to validate whether it is not {@code null} and not empty
     * @return {@code true} if the given {@link String} {@code s} is not {@code null} and not empty, {@code false}
     * otherwise
     */
    public static boolean nonEmptyOrNull(String s) {
        return Objects.nonNull(s) && !EMPTY_STRING.equals(s);
    }
}
