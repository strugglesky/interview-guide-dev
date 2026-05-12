package org.example.common.exception;

import jakarta.validation.ConstraintViolationException;
import java.net.SocketTimeoutException;
import java.util.stream.Collectors;
import javax.net.ssl.SSLException;
import org.example.common.model.ErrorCode;
import org.example.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /**
   * 处理业务异常
   */
  @ExceptionHandler(BusinessException.class)
  @ResponseStatus(HttpStatus.OK)
  public Result<Void> handleBusinessException(BusinessException e) {
    log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
    return Result.error(e.getCode(), e.getMessage());
  }

  /**
   * 处理参数校验异常
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.OK)
  public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
    String message = e.getBindingResult().getFieldErrors().stream()
        .map(FieldError::getDefaultMessage)
        .distinct()
        .collect(Collectors.joining(", "));
    log.warn("参数校验失败: {}", message);
    return Result.error(ErrorCode.BAD_REQUEST, message);
  }

  /**
   * 处理绑定异常
   */
  @ExceptionHandler(BindException.class)
  @ResponseStatus(HttpStatus.OK)
  public Result<Void> handleBindException(BindException e) {
    String message = e.getBindingResult().getFieldErrors().stream()
        .map(FieldError::getDefaultMessage)
        .distinct()
        .collect(Collectors.joining(", "));
    log.warn("参数绑定失败: {}", message);
    return Result.error(ErrorCode.BAD_REQUEST, message);
  }

  /**
   * 处理文件上传大小超限异常
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  @ResponseStatus(HttpStatus.OK)
  public Result<Void> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
    log.warn("文件上传大小超限", e);
    return Result.error(ErrorCode.BAD_REQUEST, "上传文件大小超过限制");
  }

  /**
   * 处理非法参数异常
   */
  @ExceptionHandler(ConstraintViolationException.class)
  @ResponseStatus(HttpStatus.OK)
  public Result<Void> handleConstraintViolationException(ConstraintViolationException e) {
    String message = e.getConstraintViolations().stream()
        .map(violation -> violation.getMessage())
        .distinct()
        .collect(Collectors.joining(", "));
    log.warn("非法参数: {}", message);
    return Result.error(ErrorCode.BAD_REQUEST, message);
  }

  /**
   * 处理 AI 服务网络异常（SSL 握手失败、连接超时等）
   * 统一返回 HTTP 200，通过业务错误码区分异常类型
   */
  @ExceptionHandler({ResourceAccessException.class, SocketTimeoutException.class, SSLException.class})
  @ResponseStatus(HttpStatus.OK)
  public Result<Void> handleAiNetworkException(Exception e) {
    log.error("AI 服务网络异常", e);
    ErrorCode errorCode = e instanceof SocketTimeoutException
        ? ErrorCode.AI_SERVICE_TIMEOUT
        : ErrorCode.AI_SERVICE_UNAVAILABLE;
    return Result.error(errorCode, errorCode.getMessage());
  }

  /**
   * 处理 AI 服务调用异常
   * 统一返回 HTTP 200，通过业务错误码区分异常类型
   */
  @ExceptionHandler(RestClientException.class)
  @ResponseStatus(HttpStatus.OK)
  public Result<Void> handleAiServiceException(RestClientException e) {
    log.error("AI 服务调用异常", e);
    return Result.error(ErrorCode.AI_SERVICE_ERROR, ErrorCode.AI_SERVICE_ERROR.getMessage());
  }

  /**
   * 处理 404 - 资源未找到异常
   */
  @ExceptionHandler(NoResourceFoundException.class)
  @ResponseStatus(HttpStatus.OK)
  public Result<Void> handleNoResourceFoundException(NoResourceFoundException e) {
    log.warn("资源未找到: {}", e.getResourcePath());
    return Result.error(ErrorCode.NOT_FOUND, "API 接口不存在");
  }

  /**
   * 处理请求方法不支持异常
   */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  @ResponseStatus(HttpStatus.OK)
  public Result<Void> handleHttpRequestMethodNotSupportedException(
      HttpRequestMethodNotSupportedException e) {
    log.warn("请求方法不支持: method={}, supportedMethods={}",
        e.getMethod(), e.getSupportedMethods());
    return Result.error(ErrorCode.METHOD_NOT_ALLOWED, "请求方法不支持: " + e.getMethod());
  }

  /**
   * 处理其他未知异常
   * 统一返回 HTTP 200，通过业务错误码区分异常类型
   */
  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.OK)
  public Result<Void> handleException(Exception e) {
    log.error("系统异常", e);
    return Result.error(ErrorCode.INTERNAL_ERROR, "系统繁忙，请稍后重试");
  }
}
