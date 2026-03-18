package com.page24.backend.intake;

/**
 * Day9 Step2 abstraction for multi-source intake.
 *
 * <p>Scope of this adapter:
 * <ul>
 *   <li>parse: raw payload -> source DTO</li>
 *   <li>transform: source DTO -> InternalOrder</li>
 *   <li>validate: InternalOrder structural/format validation only</li>
 * </ul>
 *
 * <p>Out of scope:
 * business duplicate rules (patient/provider/order) and other domain decisions.
 * Those stay in service layer.
 *
 * @param <TSourceDto> parsed source-specific DTO type
 */
public interface BaseIntakeAdapter<TSourceDto> {

    /**
     * Parse raw payload text into source-specific DTO.
     *
     * @param rawPayload raw incoming payload string (JSON/XML/etc.)
     * @return source-specific DTO
     * @throws IntakeParseException when payload format is invalid or not parseable
     */
    TSourceDto parse(String rawPayload) throws IntakeParseException;

    /**
     * Transform source DTO into internal unified model.
     *
     * @param sourceDto parsed source DTO
     * @return internal unified order
     */
    InternalOrder transform(TSourceDto sourceDto);

    /**
     * Validate only structural/format constraints of InternalOrder.
     *
     * @param internalOrder internal unified order
     * @throws IntakeValidationException when field constraints fail
     */
    void validate(InternalOrder internalOrder) throws IntakeValidationException;
}

