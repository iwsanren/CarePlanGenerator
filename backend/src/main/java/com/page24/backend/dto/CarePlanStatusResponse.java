package com.page24.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * Response returned by the polling endpoint.  It intentionally exposes only
 * the fields a client needs to decide whether it should keep polling.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CarePlanStatusResponse {

    private Long orderId;

    private String status;

    private String carePlanPreview;

    private String errorMessage;
}
