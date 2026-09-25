package com.hytechster.ewallet.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/register", "/session-expired", "/css/**", "/js/**", "/favicon.svg",
                                "/error").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/merchant/**").hasRole("MERCHANT")
                        .requestMatchers("/wallet/**").hasRole("USER")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
                        .permitAll())
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(accessDeniedHandler()))
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout")
                        .permitAll())
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self' https://fonts.googleapis.com; "
                                        + "font-src 'self' https://fonts.gstatic.com; img-src 'self' data:; "
                                        + "form-action 'self'; frame-ancestors 'none'; base-uri 'self'"))
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)));
        return http.build();
    }

    /**
     * A stale form (session timed out, so the CSRF token no longer matches) gets a clear
     * "session expired, nothing was sent" page instead of a confusing 403.
     */
    private static AccessDeniedHandler accessDeniedHandler() {
        AccessDeniedHandlerImpl standard = new AccessDeniedHandlerImpl();
        return (request, response, exception) -> {
            if (exception instanceof CsrfException) {
                response.sendRedirect(request.getContextPath() + "/session-expired");
            } else {
                standard.handle(request, response, exception);
            }
        };
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
