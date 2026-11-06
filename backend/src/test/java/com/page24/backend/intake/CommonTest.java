package com.page24.backend.intake;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit tests for the helpers shared by the intake adapters. */
class CommonTest {

    // ---------------------------------------------------------- parseUsDate

    @Test
    @DisplayName("parseUsDate reads a MM/dd/yyyy date and ignores the whitespace around it")
    void parsesUsDate() {
        assertThat(Common.parseUsDate("03/22/1985")).isEqualTo(LocalDate.of(1985, 3, 22));
        assertThat(Common.parseUsDate("  12/01/2020 ")).isEqualTo(LocalDate.of(2020, 12, 1));
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" gives no date")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("parseUsDate returns null for a missing or blank date, so an optional date stays optional")
    void blankDateGivesNull(String text) {
        assertThat(Common.parseUsDate(text)).isNull();
    }

    @ParameterizedTest(name = "\"{0}\" is rejected")
    @ValueSource(strings = {"1985-03-22", "22/03/1985", "13/01/2020", "00/10/2020", "02/32/2020", "3/22/1985", "not a date"})
    @DisplayName("parseUsDate rejects a date in another format or one that does not exist")
    void rejectsADateThatIsNotUsFormat(String text) {
        assertThatThrownBy(() -> Common.parseUsDate(text)).isInstanceOf(DateTimeParseException.class);
    }

    // ------------------------------------------------------------- safeList

    @Test
    @DisplayName("safeList turns null into an empty list and returns a real list unchanged")
    void safeListNeverReturnsNull() {
        List<String> values = List.of("a", "b");

        assertThat(Common.safeList(null)).isEmpty();
        assertThat(Common.safeList(values)).isSameAs(values);
        assertThat(Common.safeList(List.of())).isEmpty();
    }

    // -------------------------------------------------------------- parseXml

    @Test
    @DisplayName("parseXml returns a document that can be read")
    void parsesXml() {
        Document document = Common.parseXml("<order><patient id=\"7\">Jane</patient></order>");

        assertThat(document.getDocumentElement().getTagName()).isEqualTo("order");
        assertThat(document.getElementsByTagName("patient").item(0).getTextContent()).isEqualTo("Jane");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" is not valid XML")
    @ValueSource(strings = {"", "plain text", "<order><patient></order>", "<order>"})
    @DisplayName("parseXml reports text that is not well-formed XML as an IllegalArgumentException")
    void rejectsXmlThatIsNotWellFormed(String xml) {
        assertThatThrownBy(() -> Common.parseXml(xml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid XML payload")
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("parseXml refuses a document type declaration, which blocks external entity (XXE) attacks")
    void refusesDoctype() {
        String xxe = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE order [<!ENTITY secret SYSTEM \"file:///etc/passwd\">]>"
                + "<order>&secret;</order>";

        assertThatThrownBy(() -> Common.parseXml(xxe))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid XML payload");
    }

    @Test
    @DisplayName("parseXml does not expand entities declared by a DOCTYPE, so an expansion bomb is also refused")
    void refusesEntityExpansionBomb() {
        String bomb = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE lolz [<!ENTITY lol \"lol\"><!ENTITY lol2 \"&lol;&lol;&lol;&lol;\">]>"
                + "<lolz>&lol2;</lolz>";

        assertThatThrownBy(() -> Common.parseXml(bomb)).isInstanceOf(IllegalArgumentException.class);
    }
}
