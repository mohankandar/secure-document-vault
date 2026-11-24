package com.sdv.platform.web.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.sdv.platform.web.foundation.RequestBodyLimitFilter;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Task 0062: the second line behind {@link RequestBodyLimitFilter}: the JSON reader itself takes no more than the largest body. */
@Configuration
public class JsonLimitsConfiguration {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer jsonDocumentLimit(RequestBodyLimitFilter limits) {
        return builder -> builder.postConfigurer(mapper -> mapper.getFactory().setStreamReadConstraints(
                StreamReadConstraints.builder().maxDocumentLength(limits.largest()).build()));
    }
}
