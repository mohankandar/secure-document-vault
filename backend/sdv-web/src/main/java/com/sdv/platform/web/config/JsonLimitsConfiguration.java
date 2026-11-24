package com.sdv.platform.web.config;

import com.sdv.platform.web.foundation.RequestBodyLimitFilter;
import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.DeserializationFeature;

/** The JSON of requests and answers. */
@Configuration
public class JsonLimitsConfiguration {
    /** Task 0062: the second line behind {@link RequestBodyLimitFilter}: the JSON reader itself takes no more than the largest body. */
    @Bean
    JsonFactoryBuilderCustomizer jsonDocumentLimit(RequestBodyLimitFilter limits) {
        return builder -> builder.streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(limits.largest()).build());
    }

    /** Task 0070: the published API reads and writes as before Jackson 3 (recorded by HttpJsonBaselineTest). */
    @Bean
    static JsonMapperBuilderCustomizer jsonAsBefore() {
        return builder -> builder.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8);
    }
}
