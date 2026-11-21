package com.sdv.platform.security.config;

import com.sdv.platform.security.auth.LocalHeaderAuthenticationFilter;
import com.sdv.platform.security.auth.enterprise.EnterpriseJwtAuthenticationConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain sdvSecurityFilterChain(HttpSecurity http,
                                               ObjectProvider<LocalHeaderAuthenticationFilter> localFilterProvider,
                                               ObjectProvider<EnterpriseJwtAuthenticationConverter> enterpriseJwtConverterProvider,
                                               @Value("${sdv.auth.mode:disabled}") String authMode) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> response.sendError(401, "Authentication is required."))
                        .accessDeniedHandler((request, response, exception) -> response.sendError(403, "Access is denied.")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/info", "/api/v1/system/info", "/error").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable());

        String mode = authMode == null ? "disabled" : authMode.trim().toLowerCase(java.util.Locale.ROOT);
        switch (mode) {
            case "local-header" -> {
                LocalHeaderAuthenticationFilter localFilter = localFilterProvider.getIfAvailable();
                if (localFilter == null) {
                    throw new IllegalStateException("sdv.auth.mode=local-header is allowed only with the local Spring profile.");
                }
                http.addFilterBefore(localFilter, AnonymousAuthenticationFilter.class);
            }
            case "enterprise-jwt" -> {
                EnterpriseJwtAuthenticationConverter converter = enterpriseJwtConverterProvider.getIfAvailable();
                if (converter == null) {
                    throw new IllegalStateException("Enterprise JWT authentication is not configured.");
                }
                http.oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(withErrorBody(new BearerTokenAuthenticationEntryPoint()))
                        .accessDeniedHandler(withErrorBody(new BearerTokenAccessDeniedHandler()))
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
            }
            case "disabled" -> {
                // Fail closed: protected routes remain authenticated(), but no authentication mechanism is registered.
            }
            default -> throw new IllegalStateException("Unsupported sdv.auth.mode: " + authMode);
        }
        return http.build();
    }

    /**
     * Task 0036: Spring's bearer-token answers set the status and the WWW-Authenticate header but send no body. This
     * keeps both and then hands the answer to /error, which writes Vero's one error body with the reference.
     */
    public static AuthenticationEntryPoint withErrorBody(AuthenticationEntryPoint delegate) {
        return (request, response, exception) -> {
            delegate.commence(request, response, exception);
            if (!response.isCommitted()) response.sendError(response.getStatus());
        };
    }

    /** Task 0036: as {@link #withErrorBody(AuthenticationEntryPoint)}, for a refused bearer token's 403. */
    public static AccessDeniedHandler withErrorBody(AccessDeniedHandler delegate) {
        return (request, response, exception) -> {
            delegate.handle(request, response, exception);
            if (!response.isCommitted()) response.sendError(response.getStatus());
        };
    }
}
