package com.changegraph.backend.auth;

import com.changegraph.backend.common.ApiException;
import com.changegraph.backend.common.FieldErrorDetail;
import com.changegraph.backend.user.AppUserDetails;
import com.changegraph.backend.user.UserAccount;
import com.changegraph.backend.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final AuthRateLimiter rateLimiter;

    public AuthService(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            AuthRateLimiter rateLimiter) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public CurrentUserResponse register(RegisterRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        rateLimiter.check(httpRequest, "register");
        String email = normalizeEmail(request.email());
        passwordPolicy.check(request.password());
        if (!request.password().equals(request.passwordConfirmation())) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "PASSWORD_MISMATCH",
                    "Password confirmation does not match.",
                    List.of(new FieldErrorDetail("passwordConfirmation", "Password confirmation does not match.")));
        }
        if (users.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_IN_USE", "An account with that email already exists.");
        }
        UserAccount user = new UserAccount(UUID.randomUUID(), email, passwordEncoder.encode(request.password()), Instant.now());
        users.save(user);
        authenticate(email, request.password(), httpRequest, httpResponse);
        rateLimiter.reset(httpRequest, "register");
        return toResponse(user);
    }

    public CurrentUserResponse login(LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        rateLimiter.check(httpRequest, "login");
        String email = normalizeEmail(request.email());
        try {
            authenticate(email, request.password(), httpRequest, httpResponse);
        } catch (AuthenticationException ex) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password.");
        }
        rateLimiter.reset(httpRequest, "login");
        UserAccount user = users.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password."));
        return toResponse(user);
    }

    public CurrentUserResponse current(UserAccount user) {
        return toResponse(user);
    }

    private void authenticate(String email, String password, HttpServletRequest request, HttpServletResponse response) {
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email, password));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        if (!(authentication.getPrincipal() instanceof AppUserDetails)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password.");
        }
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static CurrentUserResponse toResponse(UserAccount user) {
        return new CurrentUserResponse(user.getId(), user.getEmail(), user.getCreatedAt());
    }
}
