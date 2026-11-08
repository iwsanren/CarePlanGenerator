package com.page24.backend.intake;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The registration and lookup rules of {@link IntakeAdapterFactory} that {@link IntakeAdapterFactoryTest}
 * does not reach: missing source, typed lookup, duplicate registration and the list of supported sources.
 */
class IntakeAdapterFactoryRegistryTest {

    @ParameterizedTest(name = "[{index}] \"{0}\" is a missing source")
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t"})
    @DisplayName("getAdapter rejects a null, empty or blank source with MISSING_INTAKE_SOURCE")
    void rejectsAMissingSource(String source) {
        IntakeAdapterFactory factory = new IntakeAdapterFactory(List.of(new StubAdapter("clinic-b")));

        assertThatThrownBy(() -> factory.getAdapter(source))
                .isInstanceOfSatisfying(IntakeValidationException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("MISSING_INTAKE_SOURCE");
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessage()).isEqualTo("Intake source is required");
                });
    }

    @Test
    @DisplayName("An unsupported source is reported together with the list of sources that are supported")
    void listsTheSupportedSourcesInTheError() {
        IntakeAdapterFactory factory =
                new IntakeAdapterFactory(List.of(new StubAdapter("clinic-b"), new StubAdapter("hospital-d")));

        assertThatThrownBy(() -> factory.getAdapter("nowhere"))
                .isInstanceOfSatisfying(IntakeValidationException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("UNSUPPORTED_INTAKE_SOURCE");
                    assertThat(ex.getMessage()).isEqualTo("Unsupported intake source: nowhere");
                    assertThat(ex.getDetail()).isInstanceOfSatisfying(Map.class,
                            detail -> assertThat(detail.get("supportedSources").toString())
                                    .isEqualTo("[clinic-b, hospital-d]"));
                });
    }

    @Test
    @DisplayName("getAdapter with a class returns the adapter as that type")
    void returnsTheAdapterAsTheRequestedType() {
        StubAdapter registered = new StubAdapter("clinic-b");
        IntakeAdapterFactory factory = new IntakeAdapterFactory(List.of(registered));

        StubAdapter found = factory.getAdapter("clinic-b", StubAdapter.class);

        assertThat(found).isSameAs(registered);
    }

    @Test
    @DisplayName("getAdapter with a class reports an adapter of another type, naming both types")
    void reportsATypeMismatch() {
        IntakeAdapterFactory factory = new IntakeAdapterFactory(List.of(new StubAdapter("clinic-b")));

        assertThatThrownBy(() -> factory.getAdapter("clinic-b", OtherStubAdapter.class))
                .isInstanceOfSatisfying(IntakeValidationException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INTAKE_ADAPTER_TYPE_MISMATCH");
                    assertThat(ex.getMessage()).isEqualTo("Adapter type mismatch for source: clinic-b");
                    assertThat(ex.getDetail()).isEqualTo(Map.of(
                            "expectedType", "OtherStubAdapter",
                            "actualType", "StubAdapter"));
                });
    }

    @Test
    @DisplayName("Two adapters for the same source are refused when the factory is built, ignoring letter case")
    void refusesADuplicateSource() {
        List<BaseIntakeAdapter<?>> adapters = List.of(new StubAdapter("clinic-b"), new StubAdapter(" Clinic-B "));

        assertThatThrownBy(() -> new IntakeAdapterFactory(adapters))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Duplicate intake adapter source registration: clinic-b");
    }

    @Test
    @DisplayName("supportedSources lists the registered sources in registration order")
    void listsSupportedSourcesInRegistrationOrder() {
        IntakeAdapterFactory factory = new IntakeAdapterFactory(
                List.of(new StubAdapter("pharma-corp"), new StubAdapter("clinic-b"), new StubAdapter("hospital-d")));

        assertThat(factory.supportedSources()).containsExactly("pharma-corp", "clinic-b", "hospital-d");
    }

    private static class StubAdapter implements BaseIntakeAdapter<Object> {
        private final String source;

        StubAdapter(String source) {
            this.source = source;
        }

        @Override
        public String source() {
            return source;
        }

        @Override
        public Object parse(String rawPayload) {
            return null;
        }

        @Override
        public InternalOrder transform(Object sourceDto) {
            return null;
        }

        @Override
        public void validate(InternalOrder internalOrder) {
            // not needed: only the registry is under test
        }
    }

    private static final class OtherStubAdapter extends StubAdapter {
        OtherStubAdapter() {
            super("other");
        }
    }
}
