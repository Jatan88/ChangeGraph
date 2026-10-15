package com.changegraph.backend.common;

import java.util.List;

public record ApiErrorResponse(String code, String message, List<FieldErrorDetail> fieldErrors) {
}
