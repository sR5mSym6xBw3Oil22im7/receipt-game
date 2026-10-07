package com.example.receipt.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.AuthenticationException;
import com.example.receipt.service.LoginAttemptLimiter;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final LoginAttemptLimiter loginAttemptLimiter;

    public AuthController(AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository,
                          CsrfTokenRepository csrfTokenRepository,
                          LoginAttemptLimiter loginAttemptLimiter) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.loginAttemptLimiter = loginAttemptLimiter;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken token = csrfTokenRepository.loadToken(request);
        if (token == null) {
            token = csrfTokenRepository.generateToken(request);
            csrfTokenRepository.saveToken(token, request, response);
        }
        return Map.of("token", token.getToken());
    }

    @GetMapping("/session")
    public ResponseEntity<Map<String, Object>> session(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() ||
                authentication.getAuthorities().stream().noneMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"))) {
            return ResponseEntity.ok(Map.of("authenticated", false));
        }
        return ResponseEntity.ok(Map.of("authenticated", true, "username", authentication.getName()));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody LoginRequest login,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        // 照合前に試行枠を確保する。ロック中や同時試行が上限に達している場合は、正しいパスワードでも照合しない
        LoginAttemptLimiter.Attempt attempt = loginAttemptLimiter.tryBegin(login.username());
        if (attempt == null) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", "ログインの失敗が続いたため、しばらくしてから再度お試しください。"));
        }
        // 予期しない例外で抜けた場合も、closeで試行枠を返す
        try (attempt) {
            Authentication result;
            try {
                result = authenticationManager.authenticate(
                        UsernamePasswordAuthenticationToken.unauthenticated(login.username(), login.password()));
            } catch (AuthenticationException exception) {
                attempt.failed();
                SecurityContextHolder.clearContext();
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "ユーザーIDまたはパスワードが正しくありません。"));
            }
            attempt.succeeded();
            request.getSession(true);
            request.changeSessionId();
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(result);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
            // ログイン前に発行したCSRFトークンを破棄し、次の更新要求で新しいトークンを取得させる
            csrfTokenRepository.saveToken(null, request, response);
            return ResponseEntity.ok(Map.of("redirect", "/admin/select.html"));
        }
    }

    public record LoginRequest(String username, String password) {}
}
