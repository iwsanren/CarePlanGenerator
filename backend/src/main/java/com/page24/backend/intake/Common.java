package com.page24.backend.intake;

import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Shared intake helpers for Day9 Step1/Step2.
 * Step1 parse external input into source-specific payload object.
 * Step2 transform source-specific payload into CreateOrderRequest.
 */
public final class Common {

    private Common() {
    }

    static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    public static LocalDate parseUsDate(String dateText) {
        if (dateText == null || dateText.isBlank()) {
            return null;
        }
        return LocalDate.parse(dateText.trim(), US_DATE);
    }

    /**
     * The bad request a source's adapter raises when its date of birth is present but is not a real date in that
     * source's format. Without it the {@code DateTimeParseException} escapes and the caller gets a 500.
     *
     * @param field          where the date came from in the source payload, so the caller can find it
     * @param expectedFormat the format that source is expected to use
     */
    static IntakeParseException invalidDateOfBirth(String field, String expectedFormat) {
        return new IntakeParseException(
                "INVALID_DATE_OF_BIRTH",
                "Date of birth must be a real date in the format " + expectedFormat,
                Map.of("field", field, "expectedFormat", expectedFormat));
    }

    public static List<String> safeList(List<String> values) {
        return values == null ? Collections.emptyList() : values;
    }

    public static Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

            return factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid XML payload", e);
        }
    }
}
