package com.page24.backend.intake;

import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

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

	public static String joinByComma(List<String> values) {
		if (values == null || values.isEmpty()) {
			return null;
		}
		return values.stream()
				.filter(Objects::nonNull)
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.collect(Collectors.joining(", "));
	}

	public static String joinByNewLine(List<String> values) {
		if (values == null || values.isEmpty()) {
			return null;
		}
		return values.stream()
				.filter(Objects::nonNull)
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.collect(Collectors.joining("\n"));
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
