package com.page24.backend.intake;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Factory/registry for intake adapters.
 *
 * <p>Add a new source by:
 * 1) creating a new adapter that implements BaseIntakeAdapter and returns source()
 * 2) registering it as a Spring bean (@Component)
 *
 * <p>No business service branching is needed for source lookup.
 */
@Component
public class IntakeAdapterFactory {

    private final Map<String, BaseIntakeAdapter<?>> adaptersBySource;

    public IntakeAdapterFactory(List<BaseIntakeAdapter<?>> adapters) {
        this.adaptersBySource = new LinkedHashMap<>();

        for (BaseIntakeAdapter<?> adapter : adapters) {
            String key = normalize(adapter.source());
            if (adaptersBySource.containsKey(key)) {
                throw new IllegalStateException("Duplicate intake adapter source registration: " + key);
            }
            adaptersBySource.put(key, adapter);
        }
    }

    public BaseIntakeAdapter<?> getAdapter(String source) {
        String key = normalize(source);
        BaseIntakeAdapter<?> adapter = adaptersBySource.get(key);
        if (adapter == null) {
            throw new IntakeValidationException(
                    "UNSUPPORTED_INTAKE_SOURCE",
                    "Unsupported intake source: " + source,
                    Map.of("supportedSources", adaptersBySource.keySet())
            );
        }
        return adapter;
    }

    public <TAdapter extends BaseIntakeAdapter<?>> TAdapter getAdapter(String source, Class<TAdapter> adapterClass) {
        BaseIntakeAdapter<?> adapter = getAdapter(source);
        if (!adapterClass.isInstance(adapter)) {
            throw new IntakeValidationException(
                    "INTAKE_ADAPTER_TYPE_MISMATCH",
                    "Adapter type mismatch for source: " + source,
                    Map.of(
                            "expectedType", adapterClass.getSimpleName(),
                            "actualType", adapter.getClass().getSimpleName()
                    )
            );
        }
        return adapterClass.cast(adapter);
    }

    public Collection<String> supportedSources() {
        return adaptersBySource.keySet();
    }

    private String normalize(String source) {
        if (source == null || source.isBlank()) {
            throw new IntakeValidationException("MISSING_INTAKE_SOURCE", "Intake source is required");
        }
        return source.trim().toLowerCase(Locale.ROOT);
    }
}


