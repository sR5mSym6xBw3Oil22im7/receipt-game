package com.example.receipt.monster.engine;

import com.example.receipt.dto.ReceiptItemData;
import com.example.receipt.dto.ReceiptStructuredData;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * カード生成（要件定義書 5章、アルゴリズム版数 2）。
 * 画像ハッシュの先頭32ビットを種にし、同じ入力からは必ず同じカードを作る。
 * ただしイラスト（SVG）だけは、種にカードを作った時刻を混ぜるので、作るたびに変わる。
 * 入力の構造化データは {@link PersonalInfoSanitizer} を通したものを渡すこと。
 */
public final class MonsterCardGenerator {
    public static final int ALGORITHM_VERSION = 2;
    static final int RARITY_R = 275;
    static final int RARITY_SR = 315;
    static final int RARITY_SSR = 345;

    private static final Map<String, String> ELEMENT_BY_CATEGORY = Map.of(
            "飲食", "炎", "食料品", "土", "日用品", "風", "交通・移動", "雷");
    private static final Map<String, List<String>> SKILLS = Map.of(
            "炎", List.of("業火の咆哮", "紅蓮撃", "火炎旋風"),
            "土", List.of("大地割り", "岩石砲", "地鳴り"),
            "風", List.of("烈風刃", "大竜巻", "疾風突き"),
            "雷", List.of("雷撃", "天の裁き", "紫電一閃"),
            "無", List.of("体当たり", "虚空撃", "無双乱舞"));
    private static final Map<String, List<String>> NAME_PREFIX = Map.of(
            "炎", List.of("ブレイ", "イグニ", "カグツ", "フレア"),
            "土", List.of("ガイア", "ロック", "ドワ", "グラン"),
            "風", List.of("シルフ", "ゼピュ", "ウィン", "ハヤ"),
            "雷", List.of("ボルト", "ライ", "テンペ", "スパー"),
            "無", List.of("ノア", "ヌル", "ゼロ", "ボイド"));
    private static final List<String> NAME_SUFFIX = List.of("ン", "ドラ", "モン", "ゴン", "ラス", "ビー");
    private static final DateTimeFormatter CREATED_AT_DIGITS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private MonsterCardGenerator() {
    }

    public static long seedFromSha(String sha256) {
        return Long.parseLong(sha256.substring(0, 8), 16);
    }

    /** 行テキストの文字数（空白を除く）。 */
    public static int charCount(List<String> lines) {
        if (lines == null) return 0;
        int count = 0;
        for (String line : lines) {
            if (line != null) count += line.replaceAll("(?U)\\s", "").length();
        }
        return count;
    }

    public static MonsterCard generate(ReceiptStructuredData receipt, String sha256, int charCount, String source) {
        return generate(receipt, sha256, charCount, source, LocalDateTime.now());
    }

    /** createdAt はイラストの種にだけ使う。名前・ステータスは createdAt によらない。 */
    public static MonsterCard generate(ReceiptStructuredData receipt, String sha256, int charCount, String source,
                                       LocalDateTime createdAt) {
        ReceiptStructuredData data = receipt == null
                ? new ReceiptStructuredData(null, null, "その他", null, null, null, null, List.of())
                : receipt;
        long seed = seedFromSha(sha256);
        List<ReceiptItemData> items = data.safeItems().stream()
                .filter(Objects::nonNull)
                .filter(it -> it.name() != null && !it.name().isBlank())
                .toList();
        long itemSum = 0;
        long maxAmount = 0;
        for (ReceiptItemData item : items) {
            long amount = amountOf(item);
            itemSum += amount;
            maxAmount = Math.max(maxAmount, amount);
        }
        long total = data.totalAmount() != null ? data.totalAmount() : itemSum;
        LocalDateTime purchasedAt = data.purchasedAt();
        int hour = purchasedAt != null ? purchasedAt.getHour() : 12;
        String element = determineElement(items);
        String storeCategory = data.storeCategory() == null || data.storeCategory().isBlank() ? "その他" : data.storeCategory();

        // 要素（すべて0〜1）。金額の影響は、どのステータスでも25%以下に抑える。
        double fA = Math.min(total, 30000) / 30000.0;
        double fM = Math.min(maxAmount, 10000) / 10000.0;
        double fC = Math.min(charCount, 600) / 600.0;
        double fN = Math.min(items.size(), 20) / 20.0;
        double fT = Math.min(12, Math.abs(hour - 12)) / 12.0;
        double fW = dateFactor(purchasedAt);

        int hp = 200 + (int) Math.floor(500 * (0.25 * fA + 0.30 * fC + 0.15 * fN + 0.15 * fW + 0.15 * r(seed, 0)));
        int atk = 40 + (int) Math.floor(100 * (0.25 * fM + 0.25 * fC + 0.15 * fT + 0.15 * fW + 0.20 * r(seed, 1)));
        int def = 30 + (int) Math.floor(80 * (0.15 * fA + 0.30 * fN + 0.20 * fC + 0.15 * fW + 0.20 * r(seed, 2)));
        int spd = 40 + (int) Math.floor(60 * (0.35 * fT + 0.20 * fW + 0.15 * fC + 0.10 * fN + 0.20 * r(seed, 3)));
        int luck = 5 + (int) Math.floor(20 * (0.50 * r(seed, 4) + 0.25 * fW + 0.25 * fC));

        switch (storeCategory) {
            case "スーパー" -> hp = up10(hp);
            case "コンビニ" -> spd = up10(spd);
            case "ドラッグストア" -> def = up10(def);
            case "飲食店" -> atk = up10(atk);
            default -> luck += 3;
        }
        boolean lucky = isLucky(total, purchasedAt);
        if (lucky) {
            atk = up10(atk);
            def = up10(def);
            luck += 10;
        }
        int power = (int) Math.floor(hp / 5.0 + atk + def + spd / 2.0 + luck * 2);
        double skillPower = Math.round((1.5 + 0.5 * (0.2 * fM + 0.4 * fC + 0.4 * r(seed, 5))) * 100) / 100.0;
        String skillName = SKILLS.get(element).get((int) ((seed >>> 8) % 3));
        String name = NAME_PREFIX.get(element).get((int) ((seed >>> 12) % 4)) + NAME_SUFFIX.get((int) ((seed >>> 16) % 6));
        String rarity = rarityOf(power);
        String flavor = storeCategory + "のレシートから生まれた" + element + "の魔物。" + items.size() + "品の力を宿している。";

        MonsterCard card = new MonsterCard(null, sha256, source, name, element, rarity, hp, atk, def, spd, luck, power,
                skillName, skillPower, lucky, flavor, storeCategory, null, ALGORITHM_VERSION);
        return card.withSvg(MonsterSvgRenderer.fallbackSvg(element, rarity, illustrationSeed(seed, createdAt)));
    }

    /**
     * イラストの乱数の種。画像ハッシュの種に、作成時刻 YYYYMMDDHHMMSS（14桁の数字）を混ぜて32ビットにする。
     * 1秒違いでも絵が大きく変わるよう、時刻の数字はかき混ぜてから合わせる。
     */
    public static long illustrationSeed(long seed, LocalDateTime createdAt) {
        long digits = Long.parseLong(createdAt.format(CREATED_AT_DIGITS));
        long mixed = digits * 0x9E3779B97F4A7C15L;
        return (seed ^ mixed ^ (mixed >>> 32)) & 0xFFFFFFFFL;
    }

    static String determineElement(List<ReceiptItemData> items) {
        Map<String, Long> sums = new LinkedHashMap<>();
        for (ReceiptItemData item : items) sums.merge(item.category(), amountOf(item), Long::sum);
        String best = null;
        long bestSum = -1;
        boolean found = false;
        for (Map.Entry<String, Long> entry : sums.entrySet()) {
            if (entry.getValue() > bestSum) {
                best = entry.getKey();
                bestSum = entry.getValue();
                found = true;
            }
        }
        if (!found || best == null) return "無";
        return ELEMENT_BY_CATEGORY.getOrDefault(best, "無");
    }

    static boolean isZoroMe(long total) {
        return (total >= 111 && String.valueOf(total).matches("(\\d)\\1+")) || total % 1000 == 777;
    }

    /** ①合計がゾロ目／末尾777 ②購入時刻の時と分が同じ ③日付が7のつく日 のいずれか。 */
    static boolean isLucky(long total, LocalDateTime purchasedAt) {
        if (isZoroMe(total)) return true;
        if (purchasedAt == null) return false;
        return purchasedAt.getHour() == purchasedAt.getMinute() || purchasedAt.getDayOfMonth() % 10 == 7;
    }

    static String rarityOf(int power) {
        if (power >= RARITY_SSR) return "SSR";
        if (power >= RARITY_SR) return "SR";
        if (power >= RARITY_R) return "R";
        return "N";
    }

    /** 日付要素：(日−1)/30 × 0.7 ＋ 週末なら0.3。日時がなければ0.5。 */
    static double dateFactor(LocalDateTime purchasedAt) {
        if (purchasedAt == null) return 0.5;
        DayOfWeek weekday = purchasedAt.getDayOfWeek();
        boolean weekend = weekday == DayOfWeek.SATURDAY || weekday == DayOfWeek.SUNDAY;
        return ((purchasedAt.getDayOfMonth() - 1) / 30.0) * 0.7 + (weekend ? 0.3 : 0);
    }

    private static double r(long seed, int k) {
        return ((seed >>> (k * 5)) & 255) / 255.0;
    }

    private static int up10(int value) {
        return Math.floorDiv(value * 11, 10);
    }

    private static long amountOf(ReceiptItemData item) {
        return item.amount() == null ? 0 : item.amount();
    }
}
