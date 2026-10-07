package com.example.receipt.service;

import com.example.receipt.exception.ReceiptException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceiptUploadValidatorTest {
    private final ReceiptUploadValidator validator = new ReceiptUploadValidator(5 * 1024 * 1024L);

    @Test
    void acceptsJpegAndPng() {
        validator.validate(new MockMultipartFile("file", "receipt.jpg", "image/jpeg", new byte[]{1}));
        validator.validate(new MockMultipartFile("file", "receipt.png", "image/png", new byte[]{1}));
    }

    @Test
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> validator.validate(
                new MockMultipartFile("file", "receipt.jpg", "image/jpeg", new byte[0])
        )).isInstanceOfSatisfying(ReceiptException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.code()).isEqualTo("EMPTY_FILE");
        });
    }

    @Test
    void rejectsUnsupportedMediaType() {
        assertThatThrownBy(() -> validator.validate(
                new MockMultipartFile("file", "receipt.gif", "image/gif", new byte[]{1})
        )).isInstanceOfSatisfying(ReceiptException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            assertThat(e.code()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        });
    }

    @Test
    void rejectsOversizedFile() {
        assertThatThrownBy(() -> validator.validate(
                new MockMultipartFile("file", "receipt.jpg", "image/jpeg", new byte[5 * 1024 * 1024 + 1])
        )).isInstanceOfSatisfying(ReceiptException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
            assertThat(e.code()).isEqualTo("FILE_TOO_LARGE");
        });
    }
}
