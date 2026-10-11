package poc;

import com.example.receipt.dto.ReceiptItemData;
import com.example.receipt.dto.ReceiptStructuredData;
import com.example.receipt.monster.engine.MonsterCard;
import com.example.receipt.monster.engine.MonsterCardGenerator;
import com.example.receipt.monster.engine.MonsterSvgValidator;
import com.example.receipt.monster.engine.PersonalInfoSanitizer;
import com.example.receipt.service.LoginAttemptLimiter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * レビュー用PoC（純ロジック）。結果は標準出力に "POC|ID|..." の形式で出す。
 * 本体コードは変更していない。assert は「現状の挙動の記録」であり、修正後は失敗する想定のものもある。
 */
class LogicPocTest {

    static void out(String id, String msg) { System.out.println("POC|" + id + "|" + msg); }

    // ---- SEC: SVG 検証 ----
    @Test
    void svgValidatorAcceptsCommentsWithMarkup() {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><!-- \"><script>alert(1)</script><img src=x onerror=alert(1)> --><circle cx=\"5\" cy=\"5\" r=\"2\"/></svg>";
        out("SEC-SVG-COMMENT", "isSafe=" + MonsterSvgValidator.isSafe(svg));
    }

    @Test
    void svgValidatorVariants() {
        String head = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\">";
        String[][] cases = {
            {"plain", head + "<circle cx=\"5\" cy=\"5\" r=\"2\"/></svg>"},
            {"xml-prolog", "<?xml version=\"1.0\"?>" + head + "<circle cx=\"5\" cy=\"5\" r=\"2\"/></svg>"},
            {"huge-width", "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\" width=\"99999999\" height=\"99999999\"><circle cx=\"5\" cy=\"5\" r=\"2\"/></svg>"},
            {"dasharray-tiny", head + "<path d=\"M0 0L10 10\" stroke=\"#000\" stroke-dasharray=\"0.0001\" stroke-width=\"1\"/></svg>"},
            {"single-quote-attr", "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'><circle cx='5' cy='5' r='2'/></svg>"},
            {"char-ref-in-attr", head + "<circle cx=\"&#60;\" cy=\"5\" r=\"2\"/></svg>"},
            {"style-attr", head + "<circle cx=\"5\" style=\"fill:red\" r=\"2\"/></svg>"},
            {"href-attr", head + "<circle cx=\"5\" href=\"x\" r=\"2\"/></svg>"},
            {"entity", "<!DOCTYPE svg [<!ENTITY a \"b\">]>" + head + "</svg>"},
            {"cdata", head + "<g><![CDATA[x]]></g></svg>"},
        };
        for (String[] c : cases) out("SEC-SVG-VARIANT", c[0] + " -> isSafe=" + MonsterSvgValidator.isSafe(c[1]));
        // 深いネスト（スタックの確認）
        StringBuilder sb = new StringBuilder(head);
        int depth = 4000;
        for (int i = 0; i < depth; i++) sb.append("<g>");
        for (int i = 0; i < depth; i++) sb.append("</g>");
        sb.append("</svg>");
        try {
            out("SEC-SVG-NEST", "len=" + sb.length() + " isSafe=" + MonsterSvgValidator.isSafe(sb.toString()));
        } catch (Throwable t) {
            out("SEC-SVG-NEST", "len=" + sb.length() + " THROWABLE=" + t.getClass().getName());
        }
        // 要素数の多い SVG（30000 文字以内）
        StringBuilder many = new StringBuilder(head);
        while (many.length() < 29_900) many.append("<circle cx=\"1\" cy=\"1\" r=\"1\"/>");
        many.append("</svg>");
        out("SEC-SVG-MANY", "len=" + many.length() + " isSafe=" + MonsterSvgValidator.isSafe(many.toString()));
    }

    // ---- PII ----
    @Test
    void piiEvasion() {
        String[] samples = {
            "TEL 03-1234-5678",                    // 基準（検出される）
            "TEL ０３－１２３４－５６７８",        // 全角ハイフン
            "TEL 03‐1234‐5678",                    // U+2010
            "TEL 03ー1234ー5678",                  // 長音
            "TEL 03.1234.5678",                    // ドット
            "TEL 03 1234 5678",                    // 空白
            "電話 0３-1234-5678",                  // 半角全角混在
            "090-1234-5678",
            "09012345678",
            "０９０１２３４５６７８９",              // 全角数字のみ
            "カード番号 4111 1111 1111 1111",
            "4111-1111-1111-1111",
            "4111·1111·1111·1111",                 // 中黒区切り
            "4111111111111111111111",              // 22桁
            "会員番号 1234567",
            "会員番号　１２３４５６７",
            "会員No １２３４５６７",
            "会員 No.12 34 56",                     // 区切り
            "お客様番号 1234567",
            "メンバーID 1234567",
            "メンバーズカード 1234567",
            "山田太郎様",
            "山田 太郎 様",
            "ご担当 佐藤",
            "担当：鈴木",
            "お名前 山田太郎",
            "東京都千代田区丸の内1-1-1",
            "名古屋市中区栄3-1-1",
            "大阪市北区梅田1-1",
            "〒100-0001",
            "１００－０００１",
            "foo＠example.com",                    // 全角@
            "foo@example.com",
            "foo (at) example.com",
        };
        for (String s : samples) {
            PersonalInfoSanitizer.Masked m = PersonalInfoSanitizer.mask(s);
            out("PII", String.format("%-34s -> %-30s masked=%d", s, m.text(), m.count()));
        }
    }

    @Test
    void sanitizerLeavesReceiptNumberAndCategoryUntouched() {
        ReceiptStructuredData d = new ReceiptStructuredData("店", "支店", "<img src=x onerror=alert(1)>", null, 100L, "現金",
                "090-1234-5678", List.of(new ReceiptItemData("a", "<b>", BigDecimal.ONE, 1L, 1L)));
        PersonalInfoSanitizer.Result r = PersonalInfoSanitizer.sanitize(d, List.of("x"));
        out("PII-RECEIPTNO", "receiptNumber=" + r.data().receiptNumber() + " storeCategory=" + r.data().storeCategory() + " itemCategory=" + r.data().items().get(0).category());
    }

    @Test
    void piiRegexRedos() {
        // 「会員」＋空白の連続。空白の \s* が3つ連続しているため O(n^3) になるかを測る
        for (int n : new int[]{250, 500, 1000, 2000}) {   // 4000 は初回実測で約106秒だったため省略
            String s = "会員" + " ".repeat(n) + "x";
            long t = System.nanoTime();
            PersonalInfoSanitizer.mask(s);
            long ms = (System.nanoTime() - t) / 1_000_000;
            out("PII-REDOS", "spaces=" + n + " ms=" + ms);
            if (ms > 20_000) break;
        }
        for (int n : new int[]{500, 1000}) {
            String s = "カード" + "　".repeat(n) + "x";  // 全角空白
            long t = System.nanoTime();
            PersonalInfoSanitizer.mask(s);
            out("PII-REDOS-FULLWIDTH", "spaces=" + n + " ms=" + (System.nanoTime() - t) / 1_000_000);
        }
    }

    @Test
    void piiEmailRegexIsQuadraticOnLongAlnumLine() {
        // メールアドレスの正規表現: [A-Za-z0-9._%+-]+@ ... が '@' の無い長い英数字列で O(n^2) になるか
        for (int n : new int[]{10_000, 20_000, 40_000, 80_000}) {
            String s = "a".repeat(n);
            long t = System.nanoTime();
            PersonalInfoSanitizer.mask(s);
            long ms = (System.nanoTime() - t) / 1_000_000;
            out("PII-EMAIL-QUADRATIC", "chars=" + n + " ms=" + ms);
            if (ms > 30_000) break;
        }
    }

    // ---- カード生成 ----
    @Test
    void cardGeneratorWithHostileNumbers() {
        String sha = "0123456789abcdef".repeat(4);
        long[] totals = {Long.MIN_VALUE, -30000, -1, 0, 30000, Long.MAX_VALUE};
        for (long total : totals) {
            ReceiptStructuredData d = new ReceiptStructuredData("s", "b", "スーパー", LocalDateTime.of(2026, 10, 7, 7, 7), total, "現金", "1",
                    List.of(new ReceiptItemData("x", "食料品", BigDecimal.ONE, 1L, 1L)));
            MonsterCard c = MonsterCardGenerator.generate(d, sha, 100, "PLAYER1");
            out("CARD-TOTAL", "total=" + total + " hp=" + c.hp() + " atk=" + c.atk() + " def=" + c.def() + " spd=" + c.spd() + " luck=" + c.luck() + " power=" + c.power() + " rarity=" + c.rarity());
        }
        // 品目金額の合計が Long をオーバーフロー
        ReceiptStructuredData d = new ReceiptStructuredData("s", "b", "スーパー", null, null, "現金", "1", List.of(
                new ReceiptItemData("a", "食料品", BigDecimal.ONE, 1L, Long.MAX_VALUE),
                new ReceiptItemData("b", "食料品", BigDecimal.ONE, 1L, Long.MAX_VALUE)));
        MonsterCard c = MonsterCardGenerator.generate(d, sha, 100, "PLAYER1");
        out("CARD-OVERFLOW", "hp=" + c.hp() + " atk=" + c.atk() + " power=" + c.power());
        // storeCategory が任意文字列（HTML/長文）
        ReceiptStructuredData e = new ReceiptStructuredData("s", "b", "x".repeat(200), null, 1L, "現金", "1", List.of());
        MonsterCard c2 = MonsterCardGenerator.generate(e, sha, 100, "PLAYER1");
        out("CARD-CATEGORY-LEN", "storeCategory.length=" + c2.storeCategory().length() + " flavor.length=" + c2.flavor().length());
        // charCount 負にはならない / items が null 要素
        List<ReceiptItemData> withNull = new ArrayList<>();
        withNull.add(null);
        out("CARD-NULLITEM", "ok=" + (MonsterCardGenerator.generate(new ReceiptStructuredData("s", "b", "スーパー", null, 1L, "現金", "1", withNull), sha, 1, "X") != null));
    }

    // ---- ログイン試行制限 ----
    @Test
    void limiterEvictionLetsAttackerResetAdminLock() {
        LoginAttemptLimiter l = new LoginAttemptLimiter(5, 15);
        for (int i = 0; i < 5; i++) { var a = l.tryBegin("admin"); a.failed(); }
        out("SEC-LOCK-DOS", "adminLockedAfter5Failures=" + (l.tryBegin("admin") == null));
        // 10,000 件の別ユーザー名で記録枠を埋める → admin のロック記録が追い出されるか
        for (int i = 0; i < 10_001; i++) { var a = l.tryBegin("u" + i); if (a != null) a.failed(); }
        var a = l.tryBegin("admin");
        out("SEC-LOCK-EVICT", "adminAcceptedAfterFlood=" + (a != null));
    }

    @Test
    void limiterRetainsHugeUsernames() {
        LoginAttemptLimiter l = new LoginAttemptLimiter(5, 15);
        String big = "a".repeat(1_000_000);
        for (int i = 0; i < 5; i++) { var a = l.tryBegin(big + i); a.failed(); }
        out("SEC-LIMITER-MEM", "5 x 1,000,000文字のユーザー名を保持（key.length>=1,000,000 each, 15分間）");
    }
}
