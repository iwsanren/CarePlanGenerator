package com.page24.backend.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client errors that MockMvc cannot produce (it does not enforce upload limits and no endpoint has a required
 * parameter), checked by calling the handler directly.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("An upload over the size limit becomes 413 with a fixed message")
    void shouldAnswerAnOversizedUploadWith413() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleStandardHttpError(new MaxUploadSizeExceededException(1_048_576L));

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        ApiErrorResponse body = response.getBody();
        assertThat(body.getType()).isEqualTo("error");
        assertThat(body.getCode()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(body.getMessage()).isEqualTo("Payload too large");
        assertThat(body.getHttpStatus()).isEqualTo(413);
        assertThat(body.getDetail()).isNull();
    }

    @Test
    @DisplayName("A missing request parameter becomes 400 naming the parameter")
    void shouldAnswerAMissingParameterWith400() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleStandardHttpError(new MissingServletRequestParameterException("page", "int"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        ApiErrorResponse body = response.getBody();
        assertThat(body.getType()).isEqualTo("validation");
        assertThat(body.getCode()).isEqualTo("INVALID_REQUEST_PARAM");
        assertThat(body.getDetail()).isEqualTo(Map.of("page", "is required"));
    }

    @Test
    @DisplayName("A missing multipart part becomes 400 naming the part")
    void shouldAnswerAMissingMultipartPartWith400() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleStandardHttpError(new MissingServletRequestPartException("file"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo("INVALID_REQUEST_PARAM");
        assertThat(response.getBody().getDetail()).isEqualTo(Map.of("file", "is required"));
    }

    @Test
    @DisplayName("A request the server cannot answer in an accepted format becomes 406")
    void shouldAnswerAnUnacceptableFormatWith406() {
        ResponseEntity<ApiErrorResponse> response = handler.handleStandardHttpError(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)));

        assertThat(response.getStatusCode().value()).isEqualTo(406);
        assertThat(response.getBody().getCode()).isEqualTo("NOT_ACCEPTABLE");
        assertThat(response.getBody().getMessage()).isEqualTo("Not acceptable");
    }

    @Test
    @DisplayName("The headers the exception carries are kept: Allow on 405, Accept on 415")
    void shouldKeepTheHeadersThatTheExceptionCarries() {
        ResponseEntity<ApiErrorResponse> methodNotAllowed = handler.handleStandardHttpError(
                new HttpRequestMethodNotSupportedException("DELETE", List.of("GET", "POST")));
        ResponseEntity<ApiErrorResponse> unsupportedMediaType = handler.handleStandardHttpError(
                new HttpMediaTypeNotSupportedException(
                        MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)));

        assertThat(methodNotAllowed.getStatusCode().value()).isEqualTo(405);
        assertThat(methodNotAllowed.getHeaders().getAllow()).extracting(Object::toString)
                .containsExactlyInAnyOrder("GET", "POST");
        assertThat(unsupportedMediaType.getStatusCode().value()).isEqualTo(415);
        assertThat(unsupportedMediaType.getHeaders().getAccept()).containsExactly(MediaType.APPLICATION_JSON);
    }
}
