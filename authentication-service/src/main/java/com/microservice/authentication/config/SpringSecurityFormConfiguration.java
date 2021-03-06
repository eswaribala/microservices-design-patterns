package com.microservice.authentication.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservice.authentication.autoconfigure.AuthenticationProperties;
import com.microservice.authentication.service.RedisTokenStoreService;
import com.microservice.web.common.util.CustomDefaultErrorAttributes;
import lombok.AllArgsConstructor;
import org.apache.commons.lang.StringUtils;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.OAuth2Request;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.authentication.*;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.context.request.ServletWebRequest;

import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * Spring Security Configuration for form
 */
@Configuration
@EnableWebSecurity
@AllArgsConstructor
@Order(301)
public class SpringSecurityFormConfiguration extends WebSecurityConfigurerAdapter {
    private final ObjectMapper objectMapper;

    private final CustomDefaultErrorAttributes customDefaultErrorAttributes;

    private final RedisTokenStoreService redisTokenStoreService;

    private final AuthenticationProperties properties;

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
        "/actuator/info",
        "/actuator/health",
        "/actuator/prometheus",
        "/error",
        "/.well-known/jwks.json",
        "/api/refreshToken"
    };

    @Bean
    @Override
    public AuthenticationManager authenticationManagerBean() throws Exception {
        return super.authenticationManagerBean();
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.requestMatchers()
            .antMatchers("/api/**", "/", "/error", "/actuator/**")
            .and()
                .formLogin()
                .loginProcessingUrl("/api/authenticate").permitAll()
                .successHandler(successHandler())
                .failureHandler(authenticationFailureHandler())
            .and()
                .logout()
                .logoutUrl("/api/logout")
                .deleteCookies("SESSIONID")
                .logoutSuccessHandler((request, response, authentication) -> {
                    redisTokenStoreService.removeAllTokensByAuthenticationUser(authentication);
                    response.setStatus(HttpStatus.OK.value());
                    response.getWriter().flush();
                })
                .logoutRequestMatcher(new AntPathRequestMatcher("/api/logout", HttpMethod.GET.name()))
                .invalidateHttpSession(true)
            .and()
                .csrf()
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
            .and()
                .exceptionHandling()
                .authenticationEntryPoint(new Http403ForbiddenEntryPoint())
            .and()
                .authorizeRequests()
                .antMatchers(WHITELIST).permitAll()
                .antMatchers("/actuator/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            .and()
                .oauth2ResourceServer()
                .accessDeniedHandler(this::handleErrorResponse)
                .authenticationEntryPoint(this::handleErrorResponse)
                .jwt(jwtConfigurer -> {
                    Environment environment = getApplicationContext().getEnvironment();
                    JwtDecoder jwtDecoder = environment.acceptsProfiles(Profiles.of("prod")) ? jwtDecoderProd() : jwtDecoder(properties);
                    jwtConfigurer.decoder(jwtDecoder).jwtAuthenticationConverter(jwtAuthenticationConverter());
                });
    }

    JwtDecoder jwtDecoderProd() {
        return getApplicationContext().getBean(JwtDecoder.class);
    }

    JwtDecoder jwtDecoder(AuthenticationProperties properties) {
        SecretKeySpec secretKeySpec = new SecretKeySpec(properties.getJwt().getKeyValue().getBytes(StandardCharsets.UTF_8), "HS256");
        return NimbusJwtDecoder.withSecretKey(secretKeySpec).build();
    }

    private void handleErrorResponse(HttpServletRequest request, HttpServletResponse response, Exception exception) throws IOException {
        HttpStatus status = customDefaultErrorAttributes.getHttpStatusError(exception);
        Map<String, Object> errorAttributes = customDefaultErrorAttributes.getErrorAttributes(new ServletWebRequest(request), ErrorAttributeOptions.defaults());
        errorAttributes.put("message", exception.getLocalizedMessage());
        errorAttributes.put("status", status.value());
        response.setStatus(status.value());
        response.addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().append(objectMapper.writeValueAsString(errorAttributes));
    }

    private AuthenticationFailureHandler authenticationFailureHandler() {
        return (request, response, exception) -> {
            request.setAttribute(DefaultErrorAttributes.class.getName() + ".ERROR", exception);
            if (validateApiPath(request)) {
                Map<String, Object> errorAttributes = customDefaultErrorAttributes.getErrorAttributes(new ServletWebRequest(request), ErrorAttributeOptions.defaults());
                response.setStatus(Integer.parseInt(errorAttributes.get("status").toString()));
                response.addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().append(objectMapper.writeValueAsString(errorAttributes));
            } else {
                new SimpleUrlAuthenticationFailureHandler().onAuthenticationFailure(request, response, exception);
            }
        };
    }

    private boolean validateApiPath(HttpServletRequest request) {
        return StringUtils.isNotBlank(request.getPathInfo()) && request.getPathInfo().startsWith("/api/") ||
            StringUtils.isNotBlank(request.getServletPath()) && request.getServletPath().startsWith("/api/");
    }

    private AuthenticationSuccessHandler successHandler() {
        return (request, response, authentication) -> {
            if (validateApiPath(request)) {
                OAuth2Request oAuth2Request = new OAuth2Request(null, authentication.getName(), authentication.getAuthorities(),
                    true, Collections.singleton("read"), null, null, null, null);
                OAuth2Authentication oAuth2Authentication = new OAuth2Authentication(oAuth2Request, authentication);
                OAuth2AccessToken token = redisTokenStoreService.generateToken(oAuth2Authentication);
                response.addHeader(HttpHeaders.AUTHORIZATION, String.format("%s %s", token.getTokenType(), token.getValue()));
                response.addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                response.addHeader("sessionId", request.getSession().getId());
                response.setStatus(HttpStatus.OK.value());
                response.getWriter().append(objectMapper.writeValueAsString(token));
            } else {
                new SavedRequestAwareAuthenticationSuccessHandler().onAuthenticationSuccess(request, response, authentication);
            }
        };
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter jwtGrantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        jwtGrantedAuthoritiesConverter.setAuthoritiesClaimName("authorities");
        jwtGrantedAuthoritiesConverter.setAuthorityPrefix("");
        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(jwtGrantedAuthoritiesConverter);
        return jwtAuthenticationConverter;
    }
}
