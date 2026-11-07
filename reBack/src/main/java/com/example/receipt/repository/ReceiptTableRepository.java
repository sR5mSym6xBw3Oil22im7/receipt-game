package com.example.receipt.repository;

import com.example.receipt.dto.ReceiptDetail;
import com.example.receipt.dto.ReceiptLine;
import com.example.receipt.dto.ReceiptSummary;
import com.example.receipt.dto.ReceiptStructuredData;
import com.example.receipt.dto.ReceiptItemData;
import com.example.receipt.exception.ReceiptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;

@Repository
public class ReceiptTableRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReceiptTableRepository.class);
    private static final String STRUCTURED_ITEM_SUMMARY_FK = "fk_receipt_structured_item_summary";

    private final JdbcTemplate jdbcTemplate;

    public ReceiptTableRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String createReceiptTableAndInsert(List<String> lines) {
        String tableName = ReceiptTableName.generate();
        return createReceiptTableAndInsert(tableName, lines);
    }

    public String createReceiptTableAndInsert(List<String> lines, String sha256) {
        ensureSaveSchema();
        String tableName = createEmptyReceiptTable();
        try {
            insertReceiptLines(tableName, lines);
            registerImageHash(sha256, tableName);
        } catch (RuntimeException e) {
            dropReceiptTableQuietly(tableName);
            throw e;
        }
        return tableName;
    }

    /*
     * レシートの保存は、PostgreSQLでもH2でも同じ手順で行う。
     * H2はCREATE TABLE・ALTER TABLEなどのテーブル定義の変更を実行すると、それまでの処理を自動で確定させる。
     * そのため、テーブル定義の変更（ensureSaveSchema・createEmptyReceiptTable）はトランザクションの外で先に済ませ、
     * トランザクションの中ではデータの登録・更新（insertReceiptLines・registerImageHash・insertStructuredData）だけを行う。
     * トランザクションが失敗したら、先に作ったレシートテーブルを dropReceiptTableQuietly で削除する。
     */

    /** 保存に使う共通テーブルを用意する（テーブル定義の変更。トランザクションの外で呼ぶ）。 */
    public void ensureSaveSchema() {
        ensureImageHashRegistry();
        ensureStructuredDataTables();
    }

    /** 新しいレシートテーブルを空で作る（テーブル定義の変更。トランザクションの外で呼ぶ）。 */
    public String createEmptyReceiptTable() {
        String tableName = ReceiptTableName.generate();
        createTable(tableName);
        return tableName;
    }

    /** 保存に失敗したときの後片付け。作ったレシートテーブルを削除する（削除に失敗しても元の例外を優先する）。 */
    public void dropReceiptTableQuietly(String tableName) {
        assertSafeTableName(tableName);
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + tableName);
        } catch (RuntimeException e) {
            // 元の保存エラーを返すため、ここでは握りつぶす（テーブル名は内部の識別子なのでログに出してよい）
            LOGGER.warn("Failed to drop receipt table after save failure: table={}, exception={}",
                    tableName, e.getClass().getName());
        }
    }

    /**
     * 同じ画像がまだ保存されていないことを確かめる（読み取りのみ）。
     * 保存済みなら409 DUPLICATE_RECEIPT_IMAGE。ハッシュは登録済みなのにレシート本文か構造化データが欠けていれば、
     * 以前の保存が途中で失敗したものとして409 INCOMPLETE_RECEIPT_SAVE。
     * 解析時に確保したハッシュがない（サーバーの再起動でH2の内容が消えたなど）なら409 ANALYSIS_EXPIRED。
     * 画面は DUPLICATE_RECEIPT_IMAGE だけを「保存済み」として扱うので、この3つを区別して返す。
     */
    public void requireImageNotSaved(String sha256) {
        List<String> registeredTables = jdbcTemplate.query(
                "SELECT table_name FROM receipt_image_hash_registry WHERE image_sha256 = ?",
                (rs, rowNum) -> rs.getString("table_name"),
                sha256
        );
        if (registeredTables.isEmpty()) throw analysisExpired();
        String tableName = registeredTables.getFirst();
        if (tableName == null) return;
        if (isSaveComplete(tableName)) throw duplicateReceiptImage();
        throw incompleteReceiptSave(tableName);
    }

    /** 未保存として確保済みのハッシュにテーブル名を結び付ける（データの更新。トランザクションの中で呼ぶ）。 */
    public void registerImageHash(String sha256, String tableName) {
        int updated = jdbcTemplate.update(
                "UPDATE receipt_image_hash_registry SET table_name = ? " +
                        "WHERE image_sha256 = ? AND table_name IS NULL",
                tableName,
                sha256
        );
        if (updated == 0) {
            // 事前の確認の後に、別の要求が同じ画像を保存した場合（行がなければ解析時の確保が消えている）
            Integer saved = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM receipt_image_hash_registry WHERE image_sha256 = ?", Integer.class, sha256);
            if (saved == null || saved == 0) throw analysisExpired();
            throw duplicateReceiptImage();
        }
    }

    private ReceiptException analysisExpired() {
        return new ReceiptException(
                HttpStatus.CONFLICT,
                "ANALYSIS_EXPIRED",
                "解析結果の有効期限が切れています（サーバーが再起動した可能性があります）。もう一度解析してから保存してください。"
        );
    }

    private boolean isSaveComplete(String tableName) {
        if (!ReceiptTableName.isSafe(tableName) || !tableExists(tableName)) return false;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM receipt_structured_summary WHERE receipt_table_name = ?",
                Integer.class,
                tableName
        );
        return count != null && count > 0;
    }

    private ReceiptException incompleteReceiptSave(String tableName) {
        return new ReceiptException(
                HttpStatus.CONFLICT,
                "INCOMPLETE_RECEIPT_SAVE",
                "このレシート画像は、以前の保存が途中で失敗しています。レシート一覧から " + tableName
                        + " を削除してから、もう一度解析・保存してください。"
        );
    }

    public void reserveImageHash(String sha256) {
        ensureImageHashRegistry();

        List<String> registeredTables = jdbcTemplate.query(
                "SELECT table_name FROM receipt_image_hash_registry WHERE image_sha256 = ?",
                (rs, rowNum) -> rs.getString("table_name"),
                sha256
        );
        if (!registeredTables.isEmpty() && registeredTables.getFirst() != null) {
            throw duplicateReceiptImage();
        }

        try {
            int inserted = jdbcTemplate.update(
                    "INSERT INTO receipt_image_hash_registry (image_sha256, table_name) VALUES (?, NULL)",
                    sha256
            );
            if (inserted == 1) {
                return;
            }
        } catch (DuplicateKeyException e) {
            // 別リクエストが解析中に確保した未登録ハッシュなら再解析を許可する。
            List<String> currentTables = jdbcTemplate.query(
                    "SELECT table_name FROM receipt_image_hash_registry WHERE image_sha256 = ?",
                    (rs, rowNum) -> rs.getString("table_name"),
                    sha256
            );
            if (!currentTables.isEmpty() && currentTables.getFirst() == null) {
                return;
            }
            throw duplicateReceiptImage();
        }
    }

    /** 同じ画像のレシートが保存済みか（解析中に確保しただけのハッシュは含めない）。 */
    public boolean isImageSaved(String sha256) {
        ensureImageHashRegistry();
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM receipt_image_hash_registry WHERE image_sha256 = ? AND table_name IS NOT NULL",
                Integer.class,
                sha256
        );
        return count != null && count > 0;
    }

    private ReceiptException duplicateReceiptImage() {
        return new ReceiptException(
                HttpStatus.CONFLICT,
                "DUPLICATE_RECEIPT_IMAGE",
                "同じレシート画像は既にデータベースへ登録されています。"
        );
    }

    public String createReceiptTableAndInsert(String tableName, List<String> lines) {
        createTable(tableName);
        insertReceiptLines(tableName, lines);
        return tableName;
    }

    private void createTable(String tableName) {
        assertSafeTableName(tableName);

        String createSql = """
                CREATE TABLE %s (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    line_no INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """.formatted(tableName);

        jdbcTemplate.execute(createSql);
    }

    /** レシートテーブルへ行ごとの文字列を登録する（データの登録。トランザクションの中で呼ぶ）。 */
    public void insertReceiptLines(String tableName, List<String> lines) {
        assertSafeTableName(tableName);
        String insertSql = "INSERT INTO " + tableName + " (line_no, text) VALUES (?, ?)";
        jdbcTemplate.batchUpdate(insertSql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ps.setInt(1, i + 1);
                ps.setString(2, lines.get(i));
            }

            @Override
            public int getBatchSize() {
                return lines.size();
            }
        });
    }

    public void saveStructuredData(String tableName, String sha256, ReceiptStructuredData data) {
        ensureStructuredDataTables();
        insertStructuredData(tableName, sha256, data);
    }

    /** 構造化データを登録する（データの登録。ensureSaveSchema の後、トランザクションの中で呼ぶ）。 */
    public void insertStructuredData(String tableName, String sha256, ReceiptStructuredData data) {
        if (data == null) data = new ReceiptStructuredData(null, null, "その他", null, null, null, null, List.of());
        jdbcTemplate.update("INSERT INTO receipt_structured_summary (receipt_table_name,image_sha256,store_name,branch_name,store_category,purchased_at,total_amount,payment_method,receipt_number) VALUES (?,?,?,?,?,?,?,?,?)",
                tableName, sha256, data.storeName(), data.branchName(), data.storeCategory(), data.purchasedAt(), data.totalAmount(), data.paymentMethod(), data.receiptNumber());
        int number = 0;
        for (ReceiptItemData item : data.safeItems()) {
            if (item == null || item.name() == null || item.name().isBlank()) continue;
            jdbcTemplate.update("INSERT INTO receipt_structured_item (receipt_table_name,item_no,item_name,category,quantity,unit_price,amount) VALUES (?,?,?,?,?,?,?)",
                    tableName, ++number, item.name(), item.category() == null ? "その他" : item.category(), item.quantity(), item.unitPrice(), item.amount());
        }
    }

    private void ensureStructuredDataTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS receipt_structured_summary (
                id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, receipt_table_name VARCHAR(64) NOT NULL UNIQUE,
                image_sha256 VARCHAR(64) NOT NULL UNIQUE, store_name VARCHAR(255), branch_name VARCHAR(255),
                store_category VARCHAR(50), purchased_at TIMESTAMP, total_amount BIGINT, payment_method VARCHAR(100),
                receipt_number VARCHAR(100), created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS receipt_structured_item (
                id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, receipt_table_name VARCHAR(64) NOT NULL,
                item_no INTEGER NOT NULL, item_name TEXT NOT NULL, category VARCHAR(50) NOT NULL, quantity NUMERIC(12,3),
                unit_price BIGINT, amount BIGINT, created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE (receipt_table_name, item_no))
                """);
        jdbcTemplate.update("DELETE FROM receipt_structured_item " +
                "WHERE receipt_table_name NOT IN (SELECT receipt_table_name FROM receipt_structured_summary)");
        if (!structuredItemSummaryForeignKeyExists()) {
            jdbcTemplate.execute("ALTER TABLE receipt_structured_item ADD CONSTRAINT " + STRUCTURED_ITEM_SUMMARY_FK +
                    " FOREIGN KEY (receipt_table_name) REFERENCES receipt_structured_summary(receipt_table_name) ON DELETE CASCADE");
        }
    }

    private boolean structuredItemSummaryForeignKeyExists() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints " +
                        "WHERE LOWER(table_schema) = 'public' AND LOWER(table_name) = 'receipt_structured_item' " +
                        "AND LOWER(constraint_name) = LOWER(?)",
                Integer.class,
                STRUCTURED_ITEM_SUMMARY_FK
        );
        return count != null && count > 0;
    }

    private void ensureImageHashRegistry() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS receipt_image_hash_registry (
                    image_sha256 VARCHAR(64) PRIMARY KEY,
                    table_name VARCHAR(64) UNIQUE,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("ALTER TABLE receipt_image_hash_registry ALTER COLUMN table_name DROP NOT NULL");
    }

    public List<ReceiptSummary> findAllReceiptTables() {
        List<ReceiptSummary> summaries = new ArrayList<>();
        for (String tableName : findReceiptTableNames()) {
            summaries.add(summaryFor(tableName));
        }
        return summaries;
    }

    public ReceiptDetail findReceipt(String tableName) {
        requireKnownFormat(tableName);
        if (!tableExists(tableName)) {
            throw new ReceiptException(HttpStatus.NOT_FOUND, "RECEIPT_NOT_FOUND", "指定されたレシートが見つかりません。");
        }

        String sql = "SELECT line_no, text FROM " + tableName + " ORDER BY line_no";
        List<ReceiptLine> lines = jdbcTemplate.query(sql, (rs, rowNum) ->
                new ReceiptLine(rs.getInt("line_no"), rs.getString("text"))
        );
        ReceiptSummary summary = summaryFor(tableName);
        return new ReceiptDetail(summary.tableName(), summary.lineCount(), summary.createdAt(), lines);
    }

    @Transactional
    public void deleteReceipt(String tableName) {
        requireKnownFormat(tableName);
        if (!tableExists(tableName)) {
            throw new ReceiptException(HttpStatus.NOT_FOUND, "RECEIPT_NOT_FOUND", "指定されたレシートが見つかりません。");
        }
        if (tableExists("receipt_structured_summary") || tableExists("receipt_structured_item")) {
            ensureStructuredDataTables();
        }
        jdbcTemplate.execute("DROP TABLE " + tableName);
        if (tableExists("receipt_structured_item")) {
            jdbcTemplate.update("DELETE FROM receipt_structured_item WHERE receipt_table_name = ?", tableName);
        }
        if (tableExists("receipt_structured_summary")) {
            jdbcTemplate.update("DELETE FROM receipt_structured_summary WHERE receipt_table_name = ?", tableName);
        }
        // モンスターカードもレシートと同時に削除する
        if (tableExists("monster_card")) {
            jdbcTemplate.update("DELETE FROM monster_card WHERE receipt_table_name = ?", tableName);
        }
        ensureImageHashRegistry();
        jdbcTemplate.update("DELETE FROM receipt_image_hash_registry WHERE table_name = ?", tableName);
    }

    private List<String> findReceiptTableNames() {
        return jdbcTemplate.queryForList(
                        "SELECT LOWER(table_name) FROM information_schema.tables " +
                                "WHERE LOWER(table_schema) = 'public' AND LOWER(table_name) LIKE 'receipt_%' " +
                                "ORDER BY table_name DESC",
                        String.class
                ).stream()
                .filter(ReceiptTableName::isSafe)
                .toList();
    }

    private ReceiptSummary summaryFor(String tableName) {
        String sql = "SELECT COUNT(*) AS line_count, MAX(created_at) AS created_at FROM " + tableName;
        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new ReceiptSummary(
                tableName,
                rs.getInt("line_count"),
                rs.getObject("created_at", OffsetDateTime.class)
        ));
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE LOWER(table_schema) = 'public' AND LOWER(table_name) = LOWER(?)",
                Integer.class,
                tableName
        );
        return count != null && count > 0;
    }

    /** 利用者が指定したテーブル名の形式が不正なら、存在しないものとして404にする（SQLには渡さない）。 */
    private static void requireKnownFormat(String tableName) {
        if (!ReceiptTableName.isSafe(tableName)) {
            throw new ReceiptException(HttpStatus.NOT_FOUND, "RECEIPT_NOT_FOUND", "指定されたレシートが見つかりません。");
        }
    }

    static void assertSafeTableName(String tableName) {
        if (!ReceiptTableName.isSafe(tableName)) {
            throw new ReceiptException(HttpStatus.INTERNAL_SERVER_ERROR, "UNSAFE_TABLE_NAME", "安全でないテーブル名が生成されました。");
        }
    }
}
