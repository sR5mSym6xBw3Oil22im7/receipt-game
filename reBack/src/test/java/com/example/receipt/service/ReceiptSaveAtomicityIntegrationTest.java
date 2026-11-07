package com.example.receipt.service;

import com.example.receipt.dto.ReceiptStructuredData;
import com.example.receipt.exception.ReceiptException;
import com.example.receipt.monster.MonsterCardService;
import com.example.receipt.monster.engine.MonsterCard;
import com.example.receipt.repository.ReceiptTableName;
import com.example.receipt.repository.ReceiptTableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * 保存の途中で失敗しても、中途半端なデータ（登録済みのハッシュだけ・空のレシートテーブル）が残らないことを確かめる。
 * テストはH2で動くが、保存処理はPostgreSQLでも同じ手順（テーブル定義の変更はトランザクションの外で先に行う）で動く。
 */
@SpringBootTest
@ActiveProfiles("test")
class ReceiptSaveAtomicityIntegrationTest {
    private static final String SHA256 = "5a5ebf200123456789abcdef0123456789abcdef0123456789abcdef01234567";
    private static final List<String> LINES = List.of("SAMPLE STORE", "ITEM 100", "TOTAL 100");
    private static final ReceiptStructuredData DATA = new ReceiptStructuredData(
            "SAMPLE STORE", null, "スーパー", null, 100L, "現金", null, List.of());

    @Autowired
    ReceiptService receiptService;

    @Autowired
    ReceiptTableRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    MonsterCardService monsterCardService;

    @BeforeEach
    void clearReceipts() {
        Mockito.reset(monsterCardService);
        jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables " +
                        "WHERE LOWER(table_schema) = 'public' AND LOWER(table_name) LIKE 'receipt_%'",
                String.class
        ).stream().filter(ReceiptTableName::isSafe).forEach(name -> jdbcTemplate.execute("DROP TABLE " + name));
        for (String table : List.of("receipt_structured_item", "receipt_structured_summary",
                "receipt_image_hash_registry", "monster_card")) {
            if (tableExists(table)) jdbcTemplate.update("DELETE FROM " + table);
        }
        // 解析時と同じく、ハッシュを未保存の状態で確保しておく
        repository.reserveImageHash(SHA256);
    }

    @Test
    void failureAfterTableCreationLeavesNoSavedHashAndNoReceiptTable() {
        doThrow(new IllegalStateException("simulated failure"))
                .when(monsterCardService).activateForSavedReceipt(anyString(), any(MonsterCard.class));

        assertThatThrownBy(() -> receiptService.store(LINES, SHA256, DATA))
                .isInstanceOf(IllegalStateException.class);

        assertThat(registeredTableName()).isNull();
        assertThat(receiptTableCount()).isZero();
        assertThat(summaryCount()).isZero();

        // 失敗の原因が解消すれば、同じ画像をそのまま保存し直せる（重複扱いにならない）
        Mockito.reset(monsterCardService);
        String tableName = receiptService.store(LINES, SHA256, DATA).tableName();

        assertThat(registeredTableName()).isEqualTo(tableName);
        assertThat(receiptTableCount()).isEqualTo(1);
        assertThat(summaryCount()).isEqualTo(1);
    }

    @Test
    void savingCompletelySavedImageAgainIsDuplicateAndCreatesNoTable() {
        receiptService.store(LINES, SHA256, DATA);

        assertThatThrownBy(() -> receiptService.store(LINES, SHA256, DATA))
                .isInstanceOfSatisfying(ReceiptException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("DUPLICATE_RECEIPT_IMAGE");
                });
        assertThat(receiptTableCount()).isEqualTo(1);
    }

    @Test
    void savingImageWhosePreviousSaveIsIncompleteIsReportedSeparately() {
        // 修正前の保存処理で途中まで残ったデータ（ハッシュとテーブルはあるが、構造化データがない）
        String leftover = ReceiptTableName.generate();
        repository.createReceiptTableAndInsert(leftover, LINES);
        jdbcTemplate.update("UPDATE receipt_image_hash_registry SET table_name = ? WHERE image_sha256 = ?",
                leftover, SHA256);

        assertThatThrownBy(() -> receiptService.store(LINES, SHA256, DATA))
                .isInstanceOfSatisfying(ReceiptException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("INCOMPLETE_RECEIPT_SAVE");
                    assertThat(e.getMessage()).contains(leftover);
                });
        assertThat(receiptTableCount()).isEqualTo(1);
        assertThat(registeredTableName()).isEqualTo(leftover);
    }

    @Test
    void savingImageWhoseReservationIsGoneIsNotTreatedAsDuplicate() {
        // サーバーの再起動でH2の内容が消えた場合など、解析時に確保したハッシュがない
        jdbcTemplate.update("DELETE FROM receipt_image_hash_registry WHERE image_sha256 = ?", SHA256);

        assertThatThrownBy(() -> receiptService.store(LINES, SHA256, DATA))
                .isInstanceOfSatisfying(ReceiptException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("ANALYSIS_EXPIRED");
                });
        assertThat(receiptTableCount()).isZero();
    }

    private boolean tableExists(String table) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE LOWER(table_schema) = 'public' AND LOWER(table_name) = ?", Integer.class, table);
        return count != null && count > 0;
    }

    private String registeredTableName() {
        return jdbcTemplate.queryForObject(
                "SELECT table_name FROM receipt_image_hash_registry WHERE image_sha256 = ?", String.class, SHA256);
    }

    private int summaryCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM receipt_structured_summary", Integer.class);
        return count == null ? 0 : count;
    }

    private long receiptTableCount() {
        return jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables " +
                        "WHERE LOWER(table_schema) = 'public' AND LOWER(table_name) LIKE 'receipt_%'",
                String.class
        ).stream().filter(ReceiptTableName::isSafe).count();
    }
}
