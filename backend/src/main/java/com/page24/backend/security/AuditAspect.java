package com.page24.backend.security;

import com.page24.backend.dto.OrderResponse;
import com.page24.backend.entity.AuditEvent;
import com.page24.backend.repository.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.time.Instant;

/** Records successful operations without storing request bodies, PHI or tokens. */
@Aspect
@Component
@Profile("!lambda")
@Order(300)
@RequiredArgsConstructor
public class AuditAspect {
    private final AuditEventRepository repository;

    @Around("@annotation(audited)")
    public Object record(ProceedingJoinPoint point, Audited audited) throws Throwable {
        Object result = point.proceed();
        AuditEvent event = new AuditEvent();
        event.setActorSubject(Actor.subject());
        event.setAction(audited.value());
        event.setOccurredAt(Instant.now());
        if (result instanceof OrderResponse order) event.setTargetId(order.getId());
        else if (point.getArgs().length > 0 && point.getArgs()[0] instanceof Long id) event.setTargetId(id);
        repository.save(event);
        return result;
    }
}
