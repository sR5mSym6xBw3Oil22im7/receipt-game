package com.example.receipt.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfFilter;
@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @Profile("!test")
    UserDetailsService userDetailsService(
            @Value("${app.auth.admin-username}") String username,
            @Value("${app.auth.admin-password-hash}") String passwordHash) {
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(passwordHash)
                .roles("ADMIN")
                .build());
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository(@Value("${app.auth.cookie-secure:true}") boolean secure) {
        CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
        repository.setCookieCustomizer(cookie -> cookie.path("/").secure(secure).sameSite("Lax"));
        return repository;
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                             CookieCsrfTokenRepository csrfRepository,
                                             SecurityContextRepository contextRepository) throws Exception {
        http
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(request -> {
                            if (!CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)) return false;
                            String path = request.getRequestURI();
                            if (path.equals("/api/receipts") || path.startsWith("/api/receipts/")) {
                                Authentication current = SecurityContextHolder.getContext().getAuthentication();
                                return current != null && current.isAuthenticated();
                            }
                            return true;
                        }))
                .securityContext(context -> context.securityContextRepository(contextRepository))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health", "/api/auth/csrf", "/api/auth/login", "/api/auth/session", "/admin/login.html", "/admin/login.js", "/admin/styles.css", "/admin/config.js", "/admin/admin-api.js").permitAll()
                        // 解析・保存・一覧・詳細・削除はすべて管理者限定（メソッドを問わない）
                        .requestMatchers("/api/receipts", "/api/receipts/**").hasRole("ADMIN")
                        .requestMatchers("/api/auth/**").authenticated()
                        // 上記以外の/api配下は既定で拒否し、新規APIが意図せず公開されないようにする
                        .requestMatchers("/api/**").denyAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            if (request.getRequestURI().startsWith("/admin/")) {
                                response.sendRedirect("/admin/login.html");
                            } else {
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                            }
                        })
                        .accessDeniedHandler((request, response, exception) -> response.sendError(HttpServletResponse.SC_FORBIDDEN)))
                .logout(logout -> logout.logoutUrl("/api/auth/logout").logoutSuccessHandler((request, response, authentication) -> response.setStatus(HttpServletResponse.SC_NO_CONTENT)))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());
        return http.build();
    }
}
