package com.page24.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Response returned by the polling endpoint.  It intentionally exposes only
 * the fields a client needs to decide whether it should keep polling.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CarePlanStatusResponse {

    @JsonProperty("order_id")
    private Long orderId;

    private String status;

    @JsonProperty("careplan_preview")
    private String carePlanPreview;

    @JsonProperty("error_message")
    private String errorMessage;
}
