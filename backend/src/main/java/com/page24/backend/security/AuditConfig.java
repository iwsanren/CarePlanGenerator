package com.page24.backend.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!lambda")
@EnableAspectJAutoProxy
@org.springframework.transaction.annotation.EnableTransactionManagement(order = 250)
public class AuditConfig {}
