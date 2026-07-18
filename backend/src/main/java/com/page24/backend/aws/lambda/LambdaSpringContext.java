package com.page24.backend.aws.lambda;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

final class LambdaSpringContext {

    private static volatile ConfigurableApplicationContext context;

    private LambdaSpringContext() {
    }

    static ConfigurableApplicationContext getContext() {
        if (context == null) {
            synchronized (LambdaSpringContext.class) {
                if (context == null) {
                    context = new SpringApplicationBuilder(LambdaApplication.class)
                            .profiles("lambda")
                            .web(WebApplicationType.NONE)
                            .run();
                }
            }
        }
        return context;
    }
}
