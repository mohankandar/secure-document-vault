package com.sdv.platform.web.licensing;

import com.documentplatform.licensing.LicenceService;
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

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LicenceGate(licence)).addPathPatterns("/api/v1/ingestion/**");
        registry.addInterceptor(seats).addPathPatterns("/api/**");
    }
}
