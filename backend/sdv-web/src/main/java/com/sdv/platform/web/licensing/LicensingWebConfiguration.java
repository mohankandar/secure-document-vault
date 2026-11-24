package com.sdv.platform.web.licensing;

import com.documentplatform.licensing.LicenceService;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class LicensingWebConfiguration implements WebMvcConfigurer {
    private final LicenceService licence;
    private final SeatMeter seats;

    public LicensingWebConfiguration(LicenceService licence, SeatMeter seats) {
        this.licence = licence;
        this.seats = seats;
    }

    /**
     * The module puts the licence into the overall health address, which would then wait for the database while that is away
     * (and turn down with it). The licence is one of the checks instead (task 0074); the overall address stays as it was.
     */
    @Bean
    static BeanDefinitionRegistryPostProcessor licenceNotInTheOverallHealth() {
        return registry -> {
            if (registry.containsBeanDefinition("licensingHealthIndicator")) registry.removeBeanDefinition("licensingHealthIndicator");
        };
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LicenceGate(licence)).addPathPatterns("/api/v1/ingestion/**");
        registry.addInterceptor(seats).addPathPatterns("/api/**");
    }
}
