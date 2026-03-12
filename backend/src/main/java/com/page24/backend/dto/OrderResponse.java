package com.page24.backend.dto;

import lombok.Data;

import java.util.List;

/**
 * 后端传给前端，后端处理完毕后返回给前端的数据。比如查询结果或操作结果。
 */
@Data
public class OrderResponse {
    private Long id;
    private Long patientId;
    private Long providerId;
    private String medicationName;
    private String status;
    private String carePlanContent;

    // Day 8: 简单返回类型（SUCCESS / WARNING）
    private String resultType;
    private String message;
    private List<String> warnings;
    private Boolean requiresConfirm;
}

