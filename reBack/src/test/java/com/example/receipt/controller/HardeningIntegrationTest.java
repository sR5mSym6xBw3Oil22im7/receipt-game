package com.example.receipt.controller;

import com.example.receipt.dto.ReceiptItemData;
import com.example.receipt.dto.ReceiptStructuredData;
import com.example.receipt.monster.engine.MonsterCard;
import com.example.receipt.monster.engine.MonsterCardGenerator;
import com.example.receipt.monster.engine.MonsterParameters;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** レビュー指摘への対策（ヘッダー、ルーム存在の秘匿、カード生成の範囲、AI出力の制限）の確認。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:receipt-hardening;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HardeningIntegrationTest {
    private static final String SHA = "0123456789abcdef".repeat(4);

    @Autowired MockMvc mockMvc;

    @Test
    void securityHeadersAreSent() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("script-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().exists("Permissions-Policy"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void missingRoomAndMissingSeatLookTheSame() throws Exception {
        var missing = mockMvc.perform(get("/api/monster/rooms/ZZZZZZ")).andExpect(status().isForbidden()).andReturn();
        assertThat(missing.getResponse().getContentAsString()).contains("SEAT_REQUIRED");
    }

    @Test
    void hostileNumbersCannotPushStatsOutsideTheDefinedRanges() {
        for (long total : new long[]{Long.MIN_VALUE, -30000, 0, Long.MAX_VALUE}) {
            ReceiptStructuredData d = new ReceiptStructuredData("s", "b", "スーパー", LocalDateTime.of(2026, 10, 7, 7, 7), total, "現金", "1",
                    List.of(new ReceiptItemData("x", "食料品", BigDecimal.ONE, 1L, Long.MAX_VALUE),
                            new ReceiptItemData("y", "食料品", BigDecimal.ONE, 1L, Long.MAX_VALUE)));
            MonsterCard c = MonsterCardGenerator.generate(d, SHA, 100, "PLAYER1");
            assertThat(c.hp()).as("total=" + total).isBetween(200, 800);
            assertThat(c.def()).isBetween(30, 130);
            assertThat(c.power()).isPositive();
        }
    }

    @Test
    void aiTextThatIsNotKatakanaOrLooksLikeALinkFallsBackToCalculatedValues() {
        MonsterCard base = MonsterCardGenerator.generate(null, SHA, 100, "PLAYER1");
        MonsterCard bad = MonsterCardGenerator.applyParameters(base,
                new MonsterParameters("Evil<b>", "炎", 300, 80, 60, 70, 10, "http://evil.example", 1.7, "詳しくは www.evil.example へ"));
        assertThat(bad.name()).isEqualTo(base.name());
        assertThat(bad.skillName()).isEqualTo(base.skillName());
        assertThat(bad.flavor()).isEqualTo(base.flavor());
        MonsterCard good = MonsterCardGenerator.applyParameters(base,
                new MonsterParameters("ホノオン", "炎", 300, 80, 60, 70, 10, "業火", 1.7, "炎をまとう小さな魔物。"));
        assertThat(good.name()).isEqualTo("ホノオン");
        assertThat(good.flavor()).isEqualTo("炎をまとう小さな魔物。");
    }

    @Test
    void unknownCategoriesFallBackToOther() {
        assertThat(MonsterCardGenerator.normalizeStoreCategory("<img src=x onerror=alert(1)>")).isEqualTo("その他");
        assertThat(MonsterCardGenerator.normalizeStoreCategory("コンビニ")).isEqualTo("コンビニ");
        assertThat(MonsterCardGenerator.normalizeItemCategory(null)).isEqualTo("その他");
        assertThat(MonsterCardGenerator.normalizeItemCategory("飲食")).isEqualTo("飲食");
    }
}
