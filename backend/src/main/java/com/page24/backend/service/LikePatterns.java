package com.page24.backend.service;

import java.util.Locale;

/**
 * Turns what a person typed into a SQL LIKE pattern that means "contains this text, ignoring case".
 *
 * The typed text must be literal: a "%" or "_" in it is a character to look for, not a wildcard (otherwise
 * searching "%" would match everybody). So "\", "%" and "_" are escaped with a backslash, and the query states
 * ESCAPE '\'. The text is lower-cased here and the column is lower-cased in SQL, so the match ignores case.
 */
final class LikePatterns {

    private LikePatterns() {
    }

    /** "O_Neil" -> "%o\_neil%". */
    static String contains(String text) {
        String escaped = text.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
