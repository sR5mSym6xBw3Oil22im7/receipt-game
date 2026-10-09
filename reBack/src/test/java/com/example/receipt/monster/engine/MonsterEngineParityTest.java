package com.example.receipt.monster.engine;

import com.example.receipt.dto.ReceiptItemData;
import com.example.receipt.dto.ReceiptStructuredData;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * デモ（static/demo/engine.js）で計算した結果（engine-parity.json）と、Javaの実装が一致することを確かめる。
 * フィクスチャは架空のレシート12件＋端のケース2件と、それらの対戦12件。
 */
class MonsterEngineParityTest {
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 10, 0, 15, 30);
    private final JsonObject fixture = load();

    @Test
    void cardsMatchTheDemoEngine() {
        JsonArray cases = fixture.getAsJsonArray("cases");
        assertThat(cases).hasSizeGreaterThan(10);
        for (JsonElement element : cases) {
            JsonObject c = element.getAsJsonObject();
            MonsterCard card = generate(c);
            JsonObject expected = c.getAsJsonObject("expected");
            String label = c.get("sha").getAsString();
            assertThat(card.name()).as(label).isEqualTo(expected.get("name").getAsString());
            assertThat(card.element()).as(label).isEqualTo(expected.get("element").getAsString());
            assertThat(card.rarity()).as(label).isEqualTo(expected.get("rarity").getAsString());
            assertThat(card.hp()).as(label).isEqualTo(expected.get("hp").getAsInt());
            assertThat(card.atk()).as(label).isEqualTo(expected.get("atk").getAsInt());
            assertThat(card.def()).as(label).isEqualTo(expected.get("def").getAsInt());
            assertThat(card.spd()).as(label).isEqualTo(expected.get("spd").getAsInt());
            assertThat(card.luck()).as(label).isEqualTo(expected.get("luck").getAsInt());
            assertThat(card.power()).as(label).isEqualTo(expected.get("power").getAsInt());
            assertThat(card.skillName()).as(label).isEqualTo(expected.get("skillName").getAsString());
            assertThat(card.skillPower()).as(label).isEqualTo(expected.get("skillPower").getAsDouble());
            assertThat(card.lucky()).as(label).isEqualTo(expected.get("lucky").getAsBoolean());
            if (!expected.get("flavor").isJsonNull()) {
                assertThat(card.flavor()).as(label).isEqualTo(expected.get("flavor").getAsString());
            }
            // 代替イラストの描き方はデモと同じ（同じ種なら同じ絵）。カードには作成時刻を混ぜた種の絵が付く。
            long seed = MonsterCardGenerator.seedFromSha(label);
            assertThat(MonsterSvgRenderer.fallbackSvg(card.element(), card.rarity(), seed)).as(label)
                    .isEqualTo(expected.get("svg").getAsString());
            assertThat(card.svg()).as(label).isEqualTo(MonsterSvgRenderer.fallbackSvg(card.element(), card.rarity(),
                    MonsterCardGenerator.illustrationSeed(seed, CREATED_AT)));
            assertThat(MonsterSvgValidator.isSafe(card.svg())).as(label).isTrue();
        }
    }

    @Test
    void sameReceiptAlwaysMakesTheSameCard() {
        JsonObject c = fixture.getAsJsonArray("cases").get(0).getAsJsonObject();
        assertThat(generate(c)).isEqualTo(generate(c));
    }

    @Test
    void creationTimeChangesOnlyTheIllustration() {
        JsonObject c = fixture.getAsJsonArray("cases").get(0).getAsJsonObject();
        MonsterCard first = generate(c, CREATED_AT);
        MonsterCard oneSecondLater = generate(c, CREATED_AT.plusSeconds(1));
        assertThat(oneSecondLater.svg()).isNotEqualTo(first.svg());
        assertThat(oneSecondLater.withSvg(null)).isEqualTo(first.withSvg(null));
        assertThat(MonsterSvgValidator.isSafe(oneSecondLater.svg())).isTrue();
    }

    @Test
    void illustrationSeedUsesTheFourteenDigitTimestamp() {
        long seed = 0x1234ABCDL;
        long expected = 20261010001530L * 0x9E3779B97F4A7C15L;
        assertThat(MonsterCardGenerator.illustrationSeed(seed, CREATED_AT))
                .isEqualTo((seed ^ expected ^ (expected >>> 32)) & 0xFFFFFFFFL)
                .isBetween(0L, 0xFFFFFFFFL);
        // 秒・年が違えば種も変わる
        assertThat(MonsterCardGenerator.illustrationSeed(seed, CREATED_AT.plusSeconds(1)))
                .isNotEqualTo(MonsterCardGenerator.illustrationSeed(seed, CREATED_AT));
        assertThat(MonsterCardGenerator.illustrationSeed(seed, CREATED_AT.plusYears(1)))
                .isNotEqualTo(MonsterCardGenerator.illustrationSeed(seed, CREATED_AT));
    }

    @Test
    void battlesMatchTheDemoEngine() {
        JsonArray cases = fixture.getAsJsonArray("cases");
        for (JsonElement element : fixture.getAsJsonArray("battles")) {
            JsonObject b = element.getAsJsonObject();
            MonsterCard a = generate(cases.get(b.get("a").getAsInt()).getAsJsonObject());
            MonsterCard d = generate(cases.get(b.get("b").getAsInt()).getAsJsonObject());
            long seed = b.get("seed").getAsLong();
            MonsterBattleEngine.Result result = MonsterBattleEngine.battle(a, d, seed);
            String label = "seed " + seed;
            assertThat(result.first()).as(label).isEqualTo(b.get("first").getAsInt());
            assertThat(result.winner()).as(label).isEqualTo(b.get("winner").getAsString());
            assertThat(result.reason()).as(label).isEqualTo(b.get("reason").getAsString());
            assertThat(result.log()).as(label).hasSize(b.get("actions").getAsInt());
            List<Integer> damages = new ArrayList<>();
            b.getAsJsonArray("damages").forEach(x -> damages.add(x.getAsInt()));
            assertThat(result.log().stream().map(MonsterBattleEngine.Action::damage).toList()).as(label).isEqualTo(damages);
            assertThat(result.finalHp()[0]).as(label).isEqualTo(b.getAsJsonArray("finalHp").get(0).getAsInt());
            assertThat(result.finalHp()[1]).as(label).isEqualTo(b.getAsJsonArray("finalHp").get(1).getAsInt());

            // 同じ battleSeed と同じ2枚なら、必ず同じ結果になる
            MonsterBattleEngine.Result again = MonsterBattleEngine.battle(a, d, seed);
            assertThat(again.log()).isEqualTo(result.log());
            assertThat(again.winner()).isEqualTo(result.winner());
        }
    }

    @Test
    void elementAdvantageFollowsTheCycle() {
        assertThat(MonsterBattleEngine.elementMultiplier("炎", "風")).isEqualTo(1.25);
        assertThat(MonsterBattleEngine.elementMultiplier("風", "土")).isEqualTo(1.25);
        assertThat(MonsterBattleEngine.elementMultiplier("土", "雷")).isEqualTo(1.25);
        assertThat(MonsterBattleEngine.elementMultiplier("雷", "炎")).isEqualTo(1.25);
        assertThat(MonsterBattleEngine.elementMultiplier("風", "炎")).isEqualTo(0.8);
        assertThat(MonsterBattleEngine.elementMultiplier("炎", "土")).isEqualTo(1.0);
        assertThat(MonsterBattleEngine.elementMultiplier("無", "炎")).isEqualTo(1.0);
    }

    private static MonsterCard generate(JsonObject c) {
        return generate(c, CREATED_AT);
    }

    private static MonsterCard generate(JsonObject c, LocalDateTime createdAt) {
        JsonObject in = c.getAsJsonObject("input");
        List<ReceiptItemData> items = new ArrayList<>();
        for (JsonElement e : in.getAsJsonArray("items")) {
            JsonObject it = e.getAsJsonObject();
            items.add(new ReceiptItemData(str(it, "name"), str(it, "category"), new BigDecimal(it.get("quantity").getAsString()),
                    it.get("unitPrice").getAsLong(), it.get("amount").getAsLong()));
        }
        String purchasedAt = str(in, "purchasedAt");
        ReceiptStructuredData data = new ReceiptStructuredData(str(in, "storeName"), str(in, "branchName"), str(in, "storeCategory"),
                purchasedAt == null ? null : LocalDateTime.parse(purchasedAt),
                in.get("totalAmount").isJsonNull() ? null : in.get("totalAmount").getAsLong(),
                str(in, "paymentMethod"), str(in, "receiptNumber"), items);
        ReceiptStructuredData sanitized = PersonalInfoSanitizer.sanitize(data, null).data();
        return MonsterCardGenerator.generate(sanitized, c.get("sha").getAsString(), c.get("charCount").getAsInt(), "ANALYZE",
                createdAt);
    }

    private static String str(JsonObject o, String key) {
        return o.get(key) == null || o.get(key).isJsonNull() ? null : o.get(key).getAsString();
    }

    private static JsonObject load() {
        try (var reader = new InputStreamReader(
                MonsterEngineParityTest.class.getResourceAsStream("/monster/engine-parity.json"), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
