package com.example.receipt.monster.engine;

import com.example.receipt.dto.ReceiptItemData;
import com.example.receipt.dto.ReceiptStructuredData;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 個人情報の除外（要件定義書 9.3：保存前の検出と除去）。
 * 自由記述の項目（店名・支店名・商品名・支払方法・行テキスト）から、
 * 会員番号・氏名・メールアドレス・カード番号・電話番号・郵便番号・住所を「（非表示）」に置き換える。
 * 取り除けなかった場合は {@link PersonalInfoException} を投げる（安全側に倒す）。
 * DOM・DBに依存しない純粋な処理で、デモの engine.js と同じ規則を同じ順で使う。
 */
public final class PersonalInfoSanitizer {
    public static final String MASK = "（非表示）";

    // 検出順：電話番号を郵便番号より先に検出し、一部だけ置換しないようにする。
    private static final List<Pattern> RULES = List.of(
            // 会員番号（空白・ハイフンで区切られた続きの数字もまとめて置換する）
            rule("(?:会員|カード|ポイント|ID)\\s*(?:番号|No\\.?)?\\s*[:：]?\\s*\\d(?:[ -]?\\d){3,}", Pattern.CASE_INSENSITIVE),
            // 氏名
            rule("(?:お名前|氏名)\\s*[:：]?\\s*[^\\s　]+"),
            rule("[^\\s　（）()]{1,10}(?:様|殿)"),
            // メールアドレス
            rule("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
            // カード番号
            rule("(?<![\\d,])(?:\\d[ -]?){13,19}(?![\\d,])"),
            // 電話番号
            rule("(?<![\\d-])0\\d{9,10}(?!\\d)|(?<![\\d-])0\\d{1,4}[-(\\s]\\d{1,4}[-)\\s]\\d{3,4}(?![\\d-])"
                    + "|\\(0\\d{1,4}\\)\\s?\\d{1,4}[-\\s]?\\d{3,4}(?![\\d-])"),
            // 郵便番号
            rule("〒\\s?\\d{3}-\\d{4}|(?<![\\d-])\\d{3}-\\d{4}(?![\\d-])"),
            // 住所
            rule("(?:東京都|北海道|(?:京都|大阪)府|[^\\s　]{2,3}県)[^\\s　]{1,20}?[市区町村郡][^\\s　]*")
    );

    private PersonalInfoSanitizer() {
    }

    // \s は全角スペースも含める（JavaScriptの \s と同じ）。\d は全角数字も含めて広めに検出する。
    private static Pattern rule(String regex) {
        return rule(regex, 0);
    }

    private static Pattern rule(String regex, int flags) {
        return Pattern.compile(regex, flags | Pattern.UNICODE_CHARACTER_CLASS);
    }

    public record Masked(String text, int count) {
    }

    public record Result(ReceiptStructuredData data, List<String> lines, int maskedCount) {
    }

    public static Masked mask(String text) {
        if (text == null) return new Masked(null, 0);
        String out = text;
        int count = 0;
        for (Pattern rule : RULES) {
            Matcher matcher = rule.matcher(out);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                count++;
                matcher.appendReplacement(sb, Matcher.quoteReplacement(MASK));
            }
            matcher.appendTail(sb);
            out = sb.toString();
        }
        return new Masked(out, count);
    }

    public static boolean containsPersonalInfo(String text) {
        if (text == null) return false;
        for (Pattern rule : RULES) {
            if (rule.matcher(text).find()) return true;
        }
        return false;
    }

    /** 構造化データと行テキストの両方を処理する。どちらもnullでよい。 */
    public static Result sanitize(ReceiptStructuredData data, List<String> lines) {
        int[] count = {0};
        ReceiptStructuredData cleaned = null;
        if (data != null) {
            List<ReceiptItemData> items = new ArrayList<>();
            for (ReceiptItemData item : data.safeItems()) {
                if (item == null) continue;
                items.add(new ReceiptItemData(clean(item.name(), count), item.category(), item.quantity(), item.unitPrice(), item.amount()));
            }
            cleaned = new ReceiptStructuredData(
                    clean(data.storeName(), count),
                    clean(data.branchName(), count),
                    data.storeCategory(),
                    data.purchasedAt(),
                    data.totalAmount(),
                    clean(data.paymentMethod(), count),
                    data.receiptNumber(),
                    items);
        }
        List<String> cleanedLines = null;
        if (lines != null) {
            cleanedLines = new ArrayList<>(lines.size());
            for (String line : lines) cleanedLines.add(clean(line, count));
        }
        return new Result(cleaned, cleanedLines, count[0]);
    }

    private static String clean(String value, int[] count) {
        if (value == null) return null;
        Masked masked = mask(value);
        count[0] += masked.count();
        if (containsPersonalInfo(masked.text())) {
            throw new PersonalInfoException();
        }
        return masked.text();
    }
}
