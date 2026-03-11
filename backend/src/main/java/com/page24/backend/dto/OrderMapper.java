package com.page24.backend.dto;

import com.page24.backend.entity.CarePlan;
import com.page24.backend.entity.Order;
import org.springframework.stereotype.Component;

/**
 * OrderMapper - 数据格式转换
 *
 * 职责：把数据库实体（Order + CarePlan）转换成前端需要的格式（OrderResponse）
 * 对应苍穹外卖里的 VO 组装逻辑（BeanUtils.copyProperties + 额外字段填充）
 *
 * 为什么单独放这里？
 * - Controller 不应该知道"怎么拼 Response"
 * - Service 不应该知道"前端要什么格式"
 * - Mapper 是中间人，只做格式转换，不做任何业务判断
 */
@Component
public class OrderMapper {

    /**
     * 把 Order + CarePlan 实体转换成 OrderResponse（返回给前端的格式）
     *
     * 原来在 OrderController 第 207-224 行的 toResponse() 方法
     */
    public OrderResponse toResponse(Order order, CarePlan carePlan) {
        OrderResponse response = new OrderResponse();
        response.setId(order.getId());
        response.setPatientId(order.getPatient().getId());
        response.setProviderId(order.getProvider().getId());
        response.setMedicationName(order.getMedicationName());

        if (carePlan != null) {
            response.setStatus(carePlan.getStatus().name());
            // 只有在 COMPLETED 状态时才返回内容
            if (carePlan.getStatus() == CarePlan.Status.COMPLETED) {
                response.setCarePlanContent(carePlan.getContent());
            }
        } else {
            response.setStatus("PENDING");
        }

        return response;
    }
}

