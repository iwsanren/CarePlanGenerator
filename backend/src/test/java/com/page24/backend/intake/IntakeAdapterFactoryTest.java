package com.page24.backend.intake;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntakeAdapterFactoryTest {

    private final IntakeAdapterFactory intakeAdapterFactory =
            new IntakeAdapterFactory(List.of(new StubAdapter("clinic-b")));

    @Test
    @DisplayName("getAdapter returns ClinicBAdapter by source key")
    void shouldReturnClinicBAdapter() {
        BaseIntakeAdapter<?> adapter = intakeAdapterFactory.getAdapter("clinic-b");
        assertEquals("clinic-b", adapter.source());
    }

    @Test
    @DisplayName("getAdapter normalizes source key case and spaces")
    void shouldNormalizeSourceKey() {
        BaseIntakeAdapter<?> adapter = intakeAdapterFactory.getAdapter("  CLINIC-B  ");
        assertEquals("clinic-b", adapter.source());
    }

    @Test
    @DisplayName("getAdapter throws validation exception for unsupported source")
    void shouldThrowForUnsupportedSource() {
        IntakeValidationException ex = assertThrows(
                IntakeValidationException.class,
                () -> intakeAdapterFactory.getAdapter("unknown-source")
        );

        assertEquals("UNSUPPORTED_INTAKE_SOURCE", ex.getCode());
        assertTrue(ex.getMessage().contains("Unsupported intake source"));
    }

    private static final class StubAdapter implements BaseIntakeAdapter<Object> {
        private final String source;

        private StubAdapter(String source) {
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
            // no-op for unit test stub
        }
    }
}



