package com.example.receipt.controller;

import com.example.receipt.dto.ReceiptText;
import com.example.receipt.service.ReceiptAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EICARテストファイル（ウイルス対策ソフトの検出確認用の無害な文字列）を JPEG/PNG/ZIP として送ったときの挙動。
 * 画像として偽装したEICAR・ZIP直送は拒否され、解析（Gemini呼び出し）まで進まないことを確かめる。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:receipt-eicar;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EicarUploadIntegrationTest {
    /** 標準のEICARテスト文字列（68バイト）。 */
    private static final byte[] EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JPEG_HEAD = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
    private static final byte[] PNG_HEAD = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    @Autowired MockMvc mockMvc;
    @Autowired CountingAnalyzer analyzer;

    @BeforeEach
    void reset() {
        analyzer.calls = 0;
    }

    private ResultActions analyze(String filename, String contentType, byte[] bytes) throws Exception {
        return mockMvc.perform(multipart("/api/receipts/analyze")
                .file(new MockMultipartFile("file", filename, contentType, bytes))
                .with(user("test-admin").roles("ADMIN"))
                .with(SecurityMockMvcRequestPostProcessors.csrf()));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    private static byte[] zip(String entryName, byte[] content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content);
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    void eicarDisguisedAsJpegIsRejectedWith415() throws Exception {
        analyze("eicar.jpg", "image/jpeg", EICAR).andExpect(status().isUnsupportedMediaType());
        assertThat(analyzer.calls).isZero();
    }

    @Test
    void eicarDisguisedAsPngIsRejectedWith415() throws Exception {
        analyze("eicar.png", "image/png", EICAR).andExpect(status().isUnsupportedMediaType());
        assertThat(analyzer.calls).isZero();
    }

    @Test
    void eicarSentAsPlainComFileIsRejectedWith415() throws Exception {
        analyze("eicar.com", "application/octet-stream", EICAR).andExpect(status().isUnsupportedMediaType());
        assertThat(analyzer.calls).isZero();
    }

    @Test
    void zipContainingEicarSentDirectlyToServerIsRejectedWith415() throws Exception {
        // ZIPはブラウザーで展開してから1枚ずつ送る仕様。サーバーにZIPが直接届いても受け付けない
        analyze("eicar.zip", "application/zip", zip("eicar.jpg", EICAR)).andExpect(status().isUnsupportedMediaType());
        analyze("eicar.zip", "image/jpeg", zip("eicar.jpg", EICAR)).andExpect(status().isUnsupportedMediaType());
        assertThat(analyzer.calls).isZero();
    }

    @Test
    void eicarZipEntryDoesNotBecomeAnImageEvenIfNamedAsOne() throws Exception {
        // ZIPの先頭はPK(0x50 0x4B)で、JPEG/PNGのマジックナンバーと一致しない
        analyze("eicar.png", "image/png", zip("eicar.png", EICAR)).andExpect(status().isUnsupportedMediaType());
        assertThat(analyzer.calls).isZero();
    }

    @Test
    void imageHeaderWithEicarAppendedPassesTypeCheckBecauseServerHasNoAntivirus() throws Exception {
        // 既知の限界: 先頭バイトが正しい画像にEICARを連結すると形式検査は通る。
        // このアプリには画像は保存されない（SHA-256と文字列のみ）が、ウイルス検査は行っていない
        analyze("eicar.jpg", "image/jpeg", concat(JPEG_HEAD, EICAR)).andExpect(status().isOk());
        analyze("eicar.png", "image/png", concat(PNG_HEAD, EICAR)).andExpect(status().isOk());
        assertThat(analyzer.calls).isEqualTo(2);
    }

    @TestConfiguration
    static class StubConfiguration {
        @Bean
        @Primary
        CountingAnalyzer countingAnalyzer() {
            return new CountingAnalyzer();
        }
    }

    static class CountingAnalyzer implements ReceiptAnalyzer {
        volatile int calls;

        @Override
        public ReceiptText analyze(byte[] bytes, String mimeType) {
            calls++;
            return new ReceiptText(List.of("SAMPLE STORE"));
        }
    }
}
