package com.page24.backend.validation;

public final class Icd10Codes {
    private Icd10Codes() {}

    // One ICD-10-CM code: excludes the reserved "U" category, positions 2-3 must be digits,
    // optional decimal part is 1-4 word characters.
    private static final String CODE = "[A-TV-Z]\\d{2}(?:\\.\\w{1,4})?";

    public static final String REGEX = "^" + CODE + "$";

}