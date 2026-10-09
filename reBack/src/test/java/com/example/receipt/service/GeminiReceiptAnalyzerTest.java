package com.example.receipt.service;

import com.example.receipt.config.GeminiApiKeys;
import com.example.receipt.exception.ReceiptException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiReceiptAnalyzerTest {
    @Test
    void failsCleanlyWhenConfiguredApiKeyIsMissing() {
        GeminiReceiptAnalyzer analyzer = new GeminiReceiptAnalyzer("gemini-3.5-flash-lite", new GeminiApiKeys("", "", "", "", ""));

        assertThatThrownBy(() -> analyzer.analyze(new byte[]{1, 2, 3}, "image/jpeg"))
                .isInstanceOfSatisfying(ReceiptException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.code()).isEqualTo("GEMINI_API_KEY_MISSING");
                });
    }

    @Test
    void parsesPurchasedAtInCommonReceiptFormats() {
        LocalDateTime expected = LocalDateTime.of(2026, 8, 3, 10, 53);
        assertThat(GeminiReceiptAnalyzer.parsePurchasedAt("2026-08-03T10:53:00")).isEqualTo(expected);
        assertThat(GeminiReceiptAnalyzer.parsePurchasedAt("2026-08-03T10:53")).isEqualTo(expected);
        assertThat(GeminiReceiptAnalyzer.parsePurchasedAt("2026-08-03T10:53:00+09:00")).isEqualTo(expected);
        assertThat(GeminiReceiptAnalyzer.parsePurchasedAt("2026/8/3 10:53")).isEqualTo(expected);
        assertThat(GeminiReceiptAnalyzer.parsePurchasedAt("2026-08-03 10:53:00")).isEqualTo(expected);
        assertThat(GeminiReceiptAnalyzer.parsePurchasedAt("2026/08/03")).isEqualTo(LocalDateTime.of(2026, 8, 3, 0, 0));
        assertThat(GeminiReceiptAnalyzer.parsePurchasedAt("不明")).isNull();
        assertThat(GeminiReceiptAnalyzer.parsePurchasedAt(null)).isNull();
    }

    @Test
    void rotatesImageClockwise() throws Exception {
        // 横3×縦2の画像。左上だけ赤くし、時計回り90度で右上へ移ることを確かめる
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xFF0000);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);

        byte[] rotated = GeminiReceiptAnalyzer.rotateClockwise(png.toByteArray(), "image/png", 90);

        BufferedImage result = ImageIO.read(new ByteArrayInputStream(rotated));
        assertThat(result.getWidth()).isEqualTo(2);
        assertThat(result.getHeight()).isEqualTo(3);
        assertThat(result.getRGB(1, 0) & 0xFFFFFF).isEqualTo(0xFF0000);
        assertThat(result.getRGB(0, 0) & 0xFFFFFF).isEqualTo(0x000000);
    }

    @Test
    void rotationReturnsNullForUnreadableImage() {
        assertThat(GeminiReceiptAnalyzer.rotateClockwise(new byte[]{1, 2, 3}, "image/jpeg", 90)).isNull();
    }

    private static final java.time.LocalDate TODAY = java.time.LocalDate.of(2026, 10, 9);

    @Test
    void correctsMisreadYearUsingPrintedWeekday() {
        // 2026/8/6 は木曜。年がかすれて 2017 と読まれた場合に直す
        assertThat(GeminiReceiptAnalyzer.correctYearByWeekday(
                LocalDateTime.of(2017, 8, 6, 9, 37), java.util.List.of("レジ 0101 2017/8/6(木) 9:37"), TODAY))
                .isEqualTo(LocalDateTime.of(2026, 8, 6, 9, 37));
        // 2020/8/4 と 2026/8/4 はどちらも火曜。曜日が合っていても今日以前で最も近い年にする
        assertThat(GeminiReceiptAnalyzer.correctYearByWeekday(
                LocalDateTime.of(2020, 8, 4, 12, 54), java.util.List.of("レジ 0101 2020/ 8/ 4(火) 12:54"), TODAY))
                .isEqualTo(LocalDateTime.of(2026, 8, 4, 12, 54));
    }

    @Test
    void doesNotMoveDateIntoTheFuture() {
        // 2026/12/25 は金曜だが今日より後。2020/12/25 も金曜なので、今日以前で最も近い 2020 年のまま
        LocalDateTime christmas = LocalDateTime.of(2020, 12, 25, 18, 0);
        assertThat(GeminiReceiptAnalyzer.correctYearByWeekday(christmas, java.util.List.of("2020/12/25(金)"), TODAY))
                .isEqualTo(christmas);
    }

    @Test
    void keepsYearWhenWeekdayMatchesOrIsMissing() {
        LocalDateTime correct = LocalDateTime.of(2026, 8, 3, 10, 53);
        assertThat(GeminiReceiptAnalyzer.correctYearByWeekday(correct, java.util.List.of("2026/ 8/ 3(月) 10:53"), TODAY))
                .isEqualTo(correct);
        assertThat(GeminiReceiptAnalyzer.correctYearByWeekday(correct, java.util.List.of("2026/8/3 10:53"), TODAY))
                .isEqualTo(correct);
        assertThat(GeminiReceiptAnalyzer.correctYearByWeekday(null, java.util.List.of("8/3(月)"), TODAY)).isNull();
    }

    @Test
    void yearHintTellsTodaysDate() {
        assertThat(GeminiReceiptAnalyzer.yearHint(java.time.LocalDate.of(2026, 10, 9))).contains("2026-10-09");
    }

    @Test
    void retriesWithDefaultKeyWhenAnalyzeKeyFails() {
        KeyRecordingAnalyzer analyzer = new KeyRecordingAnalyzer(new GeminiApiKeys("default-key", "", "", "", "analyze-key"));
        analyzer.failures.put("analyze-key", "GEMINI_QUOTA_EXCEEDED");

        assertThat(analyzer.analyze(new byte[]{1}, "image/jpeg").lines()).containsExactly("default-key");
        assertThat(analyzer.usedKeys).containsExactly("analyze-key", "default-key");
    }

    @Test
    void failsWhenBothAnalyzeAndDefaultKeysFail() {
        KeyRecordingAnalyzer analyzer = new KeyRecordingAnalyzer(new GeminiApiKeys("default-key", "", "", "", "analyze-key"));
        analyzer.failures.put("analyze-key", "GEMINI_API_KEY_REJECTED");
        analyzer.failures.put("default-key", "GEMINI_QUOTA_EXCEEDED");

        assertThatThrownBy(() -> analyzer.analyze(new byte[]{1}, "image/jpeg"))
                .isInstanceOfSatisfying(ReceiptException.class, e -> assertThat(e.code()).isEqualTo("GEMINI_QUOTA_EXCEEDED"));
        assertThat(analyzer.usedKeys).containsExactly("analyze-key", "default-key");
    }

    @Test
    void doesNotRetryWithDefaultKeyWhenNoTextFoundOrSameKey() {
        KeyRecordingAnalyzer noText = new KeyRecordingAnalyzer(new GeminiApiKeys("default-key", "", "", "", "analyze-key"));
        noText.failures.put("analyze-key", "NO_TEXT_FOUND");
        assertThatThrownBy(() -> noText.analyze(new byte[]{1}, "image/jpeg")).isInstanceOf(ReceiptException.class);
        assertThat(noText.usedKeys).containsExactly("analyze-key");

        // GEMINI_API_ANALYZE が未設定なら既定のキーで1回だけ
        KeyRecordingAnalyzer sameKey = new KeyRecordingAnalyzer(new GeminiApiKeys("default-key", "", "", "", ""));
        sameKey.failures.put("default-key", "GEMINI_QUOTA_EXCEEDED");
        assertThatThrownBy(() -> sameKey.analyze(new byte[]{1}, "image/jpeg")).isInstanceOf(ReceiptException.class);
        assertThat(sameKey.usedKeys).containsExactly("default-key");
    }

    /** Geminiを呼ばず、使ったキーを記録する。failures にあるキーはそのエラーで失敗する。 */
    private static class KeyRecordingAnalyzer extends GeminiReceiptAnalyzer {
        final java.util.List<String> usedKeys = new java.util.ArrayList<>();
        final java.util.Map<String, String> failures = new java.util.HashMap<>();

        KeyRecordingAnalyzer(GeminiApiKeys keys) {
            super("gemini-3.5-flash-lite", keys);
        }

        @Override
        public com.example.receipt.dto.ReceiptText analyze(byte[] imageBytes, String mimeType, String apiKey) {
            usedKeys.add(apiKey);
            String code = failures.get(apiKey);
            if (code != null) throw new ReceiptException(HttpStatus.BAD_GATEWAY, code, code);
            return new com.example.receipt.dto.ReceiptText(java.util.List.of(apiKey), null, null);
        }
    }
}
