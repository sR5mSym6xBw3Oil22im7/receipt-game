package com.example.receipt.monster.engine;

import com.example.receipt.dto.ReceiptItemData;
import com.example.receipt.dto.ReceiptStructuredData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalInfoSanitizerTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "会員番号 4000100123",
            "ポイントカード No.12345678",
            "ID:00012345",
            "お名前 山田太郎",
            "氏名：佐藤花子",
            "山田太郎様",
            "鈴木殿",
            "taro.yamada@example.com",
            "4111 1111 1111 1111",
            "4111-1111-1111-1111",
            "09012345678",
            "TEL 03-1234-5678",
            "(03)1234-5678",
            "090-1234-5678",
            "〒100-0001",
            "100-0001",
            "東京都千代田区千代田1-1",
            "神奈川県横浜市中区本町1-2-3",
            "大阪府大阪市北区梅田"
    })
    void personalInfoIsMasked(String text) {
        PersonalInfoSanitizer.Masked masked = PersonalInfoSanitizer.mask(text);
        assertThat(masked.count()).as(text).isPositive();
        assertThat(masked.text()).as(text).contains(PersonalInfoSanitizer.MASK);
        assertThat(PersonalInfoSanitizer.containsPersonalInfo(masked.text())).as(text).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "米 10kg",
            "ビタミン剤 90錠",
            "2026-10-03 10:12",
            "¥12,400",
            "まるまるマート",
            "本店",
            "クレジットカード",
            "電子マネー",
            "醤油ラーメン",
            "トイレットペーパー 12ロール",
            "缶コーヒー 2点 ¥260"
    })
    void ordinaryTextIsKept(String text) {
        PersonalInfoSanitizer.Masked masked = PersonalInfoSanitizer.mask(text);
        assertThat(masked.text()).isEqualTo(text);
        assertThat(masked.count()).isZero();
    }

    @Test
    void sanitizeRemovesPersonalInfoFromStructuredDataAndLines() {
        ReceiptStructuredData data = new ReceiptStructuredData(
                "山田太郎様", "東京都千代田区千代田1-1", "スーパー", LocalDateTime.parse("2026-10-03T10:12:00"), 12400L,
                "カード 4111-1111-1111-1111", "000123",
                List.of(new ReceiptItemData("米 10kg", "食料品", BigDecimal.ONE, 12400L, 12400L),
                        new ReceiptItemData("味噌 会員番号 4000100123", "食料品", BigDecimal.ONE, 300L, 300L)));
        List<String> lines = List.of("TEL 03-1234-5678", "taro@example.com", "米 10kg ¥12,400", "〒100-0001");

        PersonalInfoSanitizer.Result result = PersonalInfoSanitizer.sanitize(data, lines);

        String all = String.join("|", result.lines()) + "|" + result.data();
        for (String secret : List.of("山田", "千代田", "4111", "1111", "4000100123", "1234-5678", "taro@", "100-0001")) {
            assertThat(all).as(secret).doesNotContain(secret);
        }
        assertThat(result.data().items().get(0).name()).isEqualTo("米 10kg");
        assertThat(result.data().receiptNumber()).isEqualTo("000123");
        assertThat(result.data().totalAmount()).isEqualTo(12400L);
        assertThat(result.data().purchasedAt()).isEqualTo(LocalDateTime.parse("2026-10-03T10:12:00"));
        assertThat(result.lines().get(2)).isEqualTo("米 10kg ¥12,400");
        assertThat(result.maskedCount()).isGreaterThanOrEqualTo(7);
    }

    @Test
    void nullInputsAreAllowed() {
        PersonalInfoSanitizer.Result result = PersonalInfoSanitizer.sanitize(null, null);
        assertThat(result.data()).isNull();
        assertThat(result.lines()).isNull();
        assertThat(result.maskedCount()).isZero();
    }
}
