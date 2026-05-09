package org.example.common.exception;


import org.example.common.model.ErrorCode;
import org.example.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.net.SocketTimeoutException;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务异常
     */


    /**
     * 处理参数校验异常
     */


    /**
     * 处理绑定异常
     */


    /**
     * 处理文件上传大小超限异常
     */


    /**
     * 处理非法参数异常
     */


    /**
     * 处理 AI 服务网络异常（SSL握手失败、连接超时等）
     * 统一返回 HTTP 200，通过业务错误码区分异常类型
     */


    /**
     * 处理 AI 服务调用异常
     * 统一返回 HTTP 200，通过业务错误码区分异常类型
     */


    /**
     * 处理 404 - 资源未找到异常
     */


    /**
     * 处理请求方法不支持异常
     */


    /**
     * 处理其他未知异常
     * 统一返回 HTTP 200，通过业务错误码区分异常类型
     */

}
