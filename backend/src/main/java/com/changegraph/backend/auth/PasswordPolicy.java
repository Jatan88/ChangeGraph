package com.changegraph.backend.auth;

import com.changegraph.backend.common.ApiException;
import com.changegraph.backend.common.FieldErrorDetail;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class PasswordPolicy {

    private final int minLength;

    public PasswordPolicy(@Value("${changegraph.security.password.min-length:10}") int minLength) {
        this.minLength = minLength;
    }

    public int getMinLength() {
        return minLength;
    }

    public void check(String password) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (password == null || password.length() < minLength) {
            errors.add(new FieldErrorDetail("password", "Use at least " + minLength + " characters."));
        }
        if (password != null && password.length() > 72) {
            errors.add(new FieldErrorDetail("password", "Use at most 72 characters."));
        }
        if (password == null || password.chars().noneMatch(Character::isLetter)) {
            errors.add(new FieldErrorDetail("password", "Include at least one letter."));
        }
        if (password == null || password.chars().noneMatch(Character::isDigit)) {
            errors.add(new FieldErrorDetail("password", "Include at least one digit."));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "PASSWORD_POLICY", "Password does not meet the policy.", errors);
        }
    }
}
