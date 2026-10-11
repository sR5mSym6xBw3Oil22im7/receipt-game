package com.example.receipt.controller;

import com.example.receipt.dto.ErrorResponse;
import com.example.receipt.exception.ReceiptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.time.OffsetDateTime;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ReceiptException.class)
    public ResponseEntity<ErrorResponse> handleReceiptException(ReceiptException e) {
        return ResponseEntity.status(e.status())
                .body(new ErrorResponse(e.code(), e.getMessage(), OffsetDateTime.now()));
    }

    /** Bean Validation（@Valid）の違反。入力値そのものは返さず、項目名とメッセージだけを返す。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream().findFirst()
                .map(ApiExceptionHandler::describe)
                .orElse("入力値が正しくありません。");
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", detail);
    }

    private static String describe(FieldError error) {
        String message = error.getDefaultMessage();
        return "入力値が正しくありません（" + error.getField() + "）" + (message == null ? "" : "：" + message);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "リクエストの形式が正しくありません。");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Content-Typeが正しくありません。");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException e) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "画像ファイルのサイズが上限を超えています。");
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMultipart(MultipartException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_MULTIPART", "アップロードの形式が正しくありません。");
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ErrorResponse> handleIOException(IOException e) {
        return error(HttpStatus.BAD_REQUEST, "FILE_READ_ERROR", "画像ファイルを読み込めませんでした。");
    }

    /** DBの制約違反は入力の問題なので、接続障害（503）とは分ける。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException e) {
        LOGGER.warn("Data integrity violation: exception={}", e.getClass().getName());
        return error(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_DATA", "保存できない値が含まれています（長さ・文字・数値の範囲を確認してください）。");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDatabaseException(DataAccessException e) {
        LOGGER.error("Database access failed", e);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_ERROR",
                "PostgreSQLとの通信に失敗しました。時間をおいて再度保存してください。");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestParameter(MissingServletRequestParameterException e) {
        return missingRequestDataResponse();
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestPart(MissingServletRequestPartException e) {
        return missingRequestDataResponse();
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "このHTTPメソッドは利用できません。");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException e) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "指定されたページまたはファイルが見つかりません。");
    }

    private ResponseEntity<ErrorResponse> missingRequestDataResponse() {
        return error(HttpStatus.BAD_REQUEST, "MISSING_REQUEST_DATA", "必須のリクエスト項目がありません。");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        // 原因を残す（応答にはスタックトレースや例外メッセージを出さない）
        LOGGER.error("Unexpected error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "サーバー内部でエラーが発生しました。");
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, OffsetDateTime.now()));
    }
}
