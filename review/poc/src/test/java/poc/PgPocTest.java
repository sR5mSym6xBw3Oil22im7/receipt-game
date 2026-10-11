package poc;

import com.example.receipt.dto.ReceiptStructuredData;
import com.example.receipt.repository.ReceiptTableRepository;
import com.example.receipt.service.ReceiptService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

/** PostgreSQL 固有の挙動（NUL文字・孤立サロゲート）。使い捨てPostgres(docker)に接続して実行する。 */
@SpringBootTest(classes = com.example.receipt.ReceiptApplication.class, properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/postgres",
        "spring.datasource.username=postgres", "spring.datasource.password=poc",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "app.monster.ai-illustration-enabled=false"})
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(HttpPocTest.Cfg.class)
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "poc.pg", matches = "true")
class PgPocTest {
    @Autowired ReceiptService receiptService;
    @Autowired ReceiptTableRepository repo;

    static String sha() { return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64); }

    private void attempt(String name, List<String> lines, ReceiptStructuredData d) {
        String s = sha();
        repo.reserveImageHash(s);
        try {
            var r = receiptService.store(lines, s, d);
            System.out.println("POC|PG|" + name + " -> 保存成功 " + r.tableName());
        } catch (Throwable t) {
            System.out.println("POC|PG|" + name + " -> " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()).split("\n")[0]);
        }
    }

    @Test
    void postgresSpecific() {
        attempt("NUL(\\u0000)を含む行", List.of("a\u0000b"), null);
        attempt("孤立サロゲート(\\uD800)を含む行", List.of("a\uD800b"), null);
        attempt("絵文字", List.of("🍣"), null);
        attempt("storeName 300文字", List.of("x"), new ReceiptStructuredData("s".repeat(300), null, null, null, null, null, null, List.of()));
        attempt("NULを含むstoreName", List.of("x"), new ReceiptStructuredData("a\u0000b", null, null, null, null, null, null, List.of()));
        attempt("年99999の購入日時", List.of("x"), new ReceiptStructuredData("s", null, null, java.time.LocalDateTime.parse("+99999-01-01T00:00:00"), null, null, null, List.of()));
        attempt("totalAmount=Long.MAX", List.of("x"), new ReceiptStructuredData("s", null, null, null, Long.MAX_VALUE, null, null, List.of()));
    }
}
