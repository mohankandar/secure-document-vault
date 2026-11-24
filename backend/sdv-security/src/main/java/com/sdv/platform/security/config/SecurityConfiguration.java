package com.sdv.platform.security.config;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import com.sdv.platform.application.access.ActiveTenantCache;
import com.sdv.platform.security.auth.ActiveTenantFilter;
import com.sdv.platform.security.auth.EntityChoiceFilter;
import com.sdv.platform.security.auth.LocalSignInFilter;
import com.sdv.platform.security.auth.oidc.OidcJwtAuthenticationConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain sdvSecurityFilterChain(HttpSecurity http,
                                               ObjectProvider<LocalSignInFilter> localFilterProvider,
                                               ObjectProvider<OidcJwtAuthenticationConverter> oidcJwtConverterProvider,
                                               ObjectProvider<ActiveTenantCache> tenantCheckProvider,
                                               @Value("${sdv.auth.mode:disabled}") String authMode) throws Exception {
        String mode = authMode == null ? "disabled" : authMode.trim().toLowerCase(java.util.Locale.ROOT);
        // Task 0039: every signed-in request checks that its tenant is active (SDV-AUT-009). A sign-in mode without the
        // check would let a suspended tenant through, so Vero doesn't start without it.
        ActiveTenantCache tenantCheck = tenantCheckProvider.getIfAvailable();
        if (tenantCheck == null && !"disabled".equals(mode)) {
            throw new StartupFailure(SdvErrorCode.SYS_005, "The tenant check for every request is not available.");
        }
        if (tenantCheck != null) http.addFilterAfter(new ActiveTenantFilter(tenantCheck), AnonymousAuthenticationFilter.class);
        // Task 0041: a person who has not chosen an entity reaches only the entity list and activation (SDV-AUT-010).
        http.addFilterAfter(new EntityChoiceFilter(), AnonymousAuthenticationFilter.class);
        // Task 0038: every refused sign-in gets a coded answer (SignInAnswers), in every mode.
        AuthenticationEntryPoint entryPoint = SignInAnswers.entryPoint(mode);
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(SignInAnswers.accessDeniedHandler()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/info", "/api/v1/system/info", "/error").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable());

        switch (mode) {
            case "local-header" -> {
                LocalSignInFilter localFilter = localFilterProvider.getIfAvailable();
                if (localFilter == null) {
                    throw new StartupFailure(SdvErrorCode.SYS_005, "sdv.auth.mode=local-header is allowed only with the local Spring profile.");
                }
                http.addFilterBefore(localFilter, AnonymousAuthenticationFilter.class);
            }
            case "enterprise-jwt" -> {
                OidcJwtAuthenticationConverter converter = oidcJwtConverterProvider.getIfAvailable();
                if (converter == null) {
                    throw new StartupFailure(SdvErrorCode.SYS_005, "OIDC sign-in (enterprise-jwt) is not configured.");
                }
                http.oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(SignInAnswers.accessDeniedHandler())
                        .withObjectPostProcessor(bearerFailures(entryPoint))
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
            }
            case "disabled" -> {
                // Fail closed: protected routes remain authenticated(), but no authentication mechanism is registered.
                // Task 0038: their 401 is SDV-SYS-006, which says sign-in is switched off.
            }
            default -> throw new StartupFailure(SdvErrorCode.SYS_005, "sdv.auth.mode (SDV_AUTH_MODE) must be enterprise-jwt, local-header (local start only) or disabled; it is \"" + authMode + "\".");
        }
        return http.build();
    }

    /**
     * Task 0038: Spring's bearer filter answers a failure that isn't the caller's (the sign-in hub's keys can't be
     * fetched) by rethrowing it, which became a 500. Vero's failure handler answers it 503 SDV-AUT-004.
     */
    static ObjectPostProcessor<BearerTokenAuthenticationFilter> bearerFailures(AuthenticationEntryPoint entryPoint) {
        return new ObjectPostProcessor<>() {
            @Override
            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                filter.setAuthenticationFailureHandler(SignInAnswers.bearerFailureHandler(entryPoint));
                return filter;
            }
        };
    }
}
