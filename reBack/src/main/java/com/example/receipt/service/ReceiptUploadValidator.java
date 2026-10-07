package com.example.receipt.service;

import com.example.receipt.exception.ReceiptException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

@Component
public class ReceiptUploadValidator {
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of("image/jpeg", "image/png");
    private final long maxBytes;

    public ReceiptUploadValidator(@Value("${app.max-upload-bytes:5242880}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ReceiptException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "画像ファイルを選択してください。");
        }
        if (file.getSize() > maxBytes) {
            throw new ReceiptException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "画像ファイルのサイズが上限を超えています。");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_MIME_TYPES.contains(contentType.toLowerCase())) {
            throw new ReceiptException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "JPEGまたはPNG画像を指定してください。");
        }
    }
}
