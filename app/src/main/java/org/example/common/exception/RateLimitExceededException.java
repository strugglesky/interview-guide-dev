package org.example.common.exception;

import lombok.AllArgsConstructor;
import org.example.common.model.ErrorCode;


public class RateLimitExceededException extends BusinessException{
    public RateLimitExceededException() {
        super(ErrorCode.RATE_LIMIT_EXCEEDED, ErrorCode.RATE_LIMIT_EXCEEDED.getMessage());
    }

    public RateLimitExceededException(String message) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED, message);
    }

    public RateLimitExceededException(String message, Throwable cause) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED.getCode(), message);
        this.initCause(cause);
    }
}
