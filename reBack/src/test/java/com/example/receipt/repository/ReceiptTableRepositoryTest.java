package com.example.receipt.repository;

import com.example.receipt.dto.ReceiptDetail;
import com.example.receipt.dto.ReceiptItemData;
import com.example.receipt.dto.ReceiptSummary;
import com.example.receipt.dto.ReceiptStructuredData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Import(ReceiptTableRepository.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:repo;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
class ReceiptTableRepositoryTest {
    @Autowired
    ReceiptTableRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearTables() {
        jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE LOWER(table_schema) = 'public' AND LOWER(table_name) LIKE 'receipt_%'",
                String.class
        ).forEach(name -> jdbcTemplate.execute("DROP TABLE " + name + " CASCADE"));
    }

    @Test
    void createsOneIndependentTablePerReceiptAndInsertsLines() {
        String first = repository.createReceiptTableAndInsert(List.of("STORE A", "TOTAL 100"));
        String second = repository.createReceiptTableAndInsert(List.of("STORE B"));

        assertThat(first).matches("receipt_[0-9a-f]{32}");
        assertThat(second).matches("receipt_[0-9a-f]{32}");
        assertThat(second).isNotEqualTo(first);

        Integer firstCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + first, Integer.class);
        Integer secondCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + second, Integer.class);
        String firstLine = jdbcTemplate.queryForObject(
                "SELECT text FROM " + first + " WHERE line_no = 1",
                String.class
        );

        assertThat(firstCount).isEqualTo(2);
        assertThat(secondCount).isEqualTo(1);
        assertThat(firstLine).isEqualTo("STORE A");

        List<ReceiptSummary> summaries = repository.findAllReceiptTables();
        assertThat(summaries).extracting(ReceiptSummary::tableName).contains(first, second);
        assertThat(summaries).filteredOn(summary -> summary.tableName().equals(first))
                .singleElement()
                .satisfies(summary -> assertThat(summary.lineCount()).isEqualTo(2));

        ReceiptDetail detail = repository.findReceipt(first);
        assertThat(detail.tableName()).isEqualTo(first);
        assertThat(detail.lines()).extracting(line -> line.text())
                .containsExactly("STORE A", "TOTAL 100");
    }

    @Test
    void deletingReceiptAlsoDeletesItsImageHash() {
        String sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        repository.reserveImageHash(sha256);
        String tableName = repository.createReceiptTableAndInsert(List.of("STORE A"), sha256);

        repository.deleteReceipt(tableName);

        Integer hashCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM receipt_image_hash_registry WHERE image_sha256 = ?",
                Integer.class,
                sha256
        );
        assertThat(hashCount).isZero();

        repository.reserveImageHash(sha256);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM receipt_image_hash_registry WHERE image_sha256 = ?",
                Integer.class,
                sha256
        )).isEqualTo(1);
    }

    @Test
    void deletingReceiptAlsoDeletesStructuredSummaryAndItems() {
        String sha256 = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        repository.reserveImageHash(sha256);
        String tableName = repository.createReceiptTableAndInsert(List.of("STORE A", "TOTAL 100"), sha256);
        repository.saveStructuredData(tableName, sha256, new ReceiptStructuredData(
                "STORE A", null, "スーパー", null, 100L, "現金", null,
                List.of(
                        new ReceiptItemData("りんご", "食料品", BigDecimal.ONE, 50L, 50L),
                        new ReceiptItemData("パン", "食料品", BigDecimal.ONE, 50L, 50L)
                )
        ));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM receipt_structured_summary WHERE receipt_table_name = ?",
                Integer.class,
                tableName
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM receipt_structured_item WHERE receipt_table_name = ?",
                Integer.class,
                tableName
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints " +
                        "WHERE LOWER(table_name) = 'receipt_structured_item' " +
                        "AND LOWER(constraint_name) = 'fk_receipt_structured_item_summary'",
                Integer.class
        )).isEqualTo(1);

        repository.deleteReceipt(tableName);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = LOWER(?)",
                Integer.class,
                tableName
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM receipt_structured_summary WHERE receipt_table_name = ?",
                Integer.class,
                tableName
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM receipt_structured_item WHERE receipt_table_name = ?",
                Integer.class,
                tableName
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM receipt_image_hash_registry WHERE image_sha256 = ?",
                Integer.class,
                sha256
        )).isZero();
    }
}
