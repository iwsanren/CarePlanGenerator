package com.page24.backend.exception;

public class ApiErrorResponse {
    private String type;
    private String code;
    private String message;
    private Object detail;
    private int httpStatus;

    public ApiErrorResponse() {
    }

    public ApiErrorResponse(String type, String code, String message, Object detail, int httpStatus) {
        this.type = type;
        this.code = code;
        this.message = message;
        this.detail = detail;
        this.httpStatus = httpStatus;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Object getDetail() {
        return detail;
    }

    public void setDetail(Object detail) {
        this.detail = detail;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(int httpStatus) {
        this.httpStatus = httpStatus;
    }
}

