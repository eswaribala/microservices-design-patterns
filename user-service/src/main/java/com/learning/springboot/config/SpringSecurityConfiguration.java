package com.learning.springboot.config;

import com.learning.springboot.config.jwt.JwtAuthenticationConverter;
import com.learning.springboot.config.jwt.TokenProvider;
import com.learning.springboot.util.HandleResponseError;
import lombok.AllArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;

/**
 * Spring Security Configuration
 */
@Configuration
@AllArgsConstructor
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SpringSecurityConfiguration {
    private final TokenProvider tokenProvider;

    private final HandleResponseError handleResponseError;

    private static final String[] WHITELIST = {
        // -- swagger ui
        "/v2/api-docs",
        "/swagger-resources",
        "/swagger-resources/**",
        "/configuration/ui",
        "/configuration/security",
        "/swagger-ui.html",
        "/webjars/**",
        "/**/*.js",
        "/**/*.css",
        "/**/*.html",
        "/favicon.ico",
        // other public endpoints of your API may be appended to this array
        "/actuator/**"
    };

    @Bean
    public SecurityWebFilterChain configure(ServerHttpSecurity http) {
        return http
            .csrf()
                .disable()
                .headers()
                .frameOptions().disable()
                .cache().disable()
            .and()
                .formLogin().disable()
                .httpBasic().disable()
                .logout().disable()
                .authorizeExchange()
                .pathMatchers(WHITELIST).permitAll()
                .anyExchange().authenticated()
            .and()
                .addFilterAt(authenticationWebFilter(), SecurityWebFiltersOrder.AUTHENTICATION)
                .exceptionHandling()
                .authenticationEntryPoint((exchange, e) -> handleResponseError.handle(exchange, e, true))
            .and()
                .build();
    }

    private AuthenticationWebFilter authenticationWebFilter() {
        AuthenticationWebFilter authenticationWebFilter = new AuthenticationWebFilter(Mono::just);
        authenticationWebFilter.setServerAuthenticationConverter(new JwtAuthenticationConverter(tokenProvider));
        NegatedServerWebExchangeMatcher negateWhiteList = new NegatedServerWebExchangeMatcher(ServerWebExchangeMatchers.pathMatchers(WHITELIST));
        authenticationWebFilter.setRequiresAuthenticationMatcher(negateWhiteList);
        authenticationWebFilter.setAuthenticationFailureHandler(authenticationFailureHandler());
        authenticationWebFilter.setAuthenticationSuccessHandler(authenticationSuccessHandler());
        return authenticationWebFilter;
    }

    private ServerAuthenticationFailureHandler authenticationFailureHandler() {
        return (webFilterExchange, exception) -> handleResponseError.handle(webFilterExchange.getExchange(), exception, true);
    }

    private ServerAuthenticationSuccessHandler authenticationSuccessHandler() {
        return (webFilterExchange, authentication) -> webFilterExchange.getChain().filter(webFilterExchange.getExchange())
            .subscriberContext(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }


}
