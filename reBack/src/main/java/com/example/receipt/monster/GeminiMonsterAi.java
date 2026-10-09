package com.example.receipt.monster;

import com.example.receipt.dto.ReceiptText;
import com.example.receipt.monster.engine.MonsterCard;
import com.example.receipt.monster.engine.MonsterSvgRenderer;
import com.example.receipt.monster.engine.MonsterSvgValidator;
import com.example.receipt.service.GeminiReceiptAnalyzer;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Gemini を使う {@link MonsterAi}。 */
@Component
public class GeminiMonsterAi implements MonsterAi {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeminiMonsterAi.class);
    static final int CPU_TIMEOUT_MS = 20_000;
    static final int ILLUSTRATION_TIMEOUT_MS = 30_000;
    static final int MAX_REASON_LENGTH = 40;

    private final GeminiReceiptAnalyzer receiptAnalyzer;
    private final MonsterSettings settings;
    private final Gson gson = new Gson();

    public GeminiMonsterAi(GeminiReceiptAnalyzer receiptAnalyzer, MonsterSettings settings) {
        this.receiptAnalyzer = receiptAnalyzer;
        this.settings = settings;
    }

    @Override
    public ReceiptText readReceipt(byte[] imageBytes, String mimeType, String apiKey) {
        return receiptAnalyzer.analyze(imageBytes, mimeType, apiKey);
    }

    @Override
    public Optional<String> illustrate(MonsterCard card, String apiKey) {
        List<String> palette = MonsterSvgRenderer.palette(card.element());
        String prompt = """
                ゲームのモンスターカードに載せる、かわいいモンスターのイラストをSVGで1枚描いてください。
                属性：%s　レア度：%s　名前：%s
                使う色：%s（メイン）、%s（明るい色）、%s（暗い色）
                条件：
                - ルート要素は <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200">。
                - 使える要素は svg, g, defs, path, circle, ellipse, rect, polygon, polyline, line, linearGradient, radialGradient, stop だけ。
                - 文字（text）、画像、script、style、foreignObject、use、href、イベント属性は使わない。
                - グラデーションの参照は url(#id) の形だけ。
                - 30000文字以内。
                出力は指定されたJSONスキーマだけにしてください。
                """.formatted(card.element(), card.rarity(), card.name(), palette.get(0), palette.get(1), palette.get(2));
        Schema schema = Schema.builder().type(Type.Known.OBJECT)
                .properties(new LinkedHashMap<>(Map.of("svg", Schema.builder().type(Type.Known.STRING).build())))
                .required("svg")
                .build();
        try {
            String text = generate(apiKey, prompt, schema, ILLUSTRATION_TIMEOUT_MS);
            JsonObject parsed = gson.fromJson(text, JsonObject.class);
            String svg = parsed == null || !parsed.has("svg") ? null : parsed.get("svg").getAsString().trim();
            if (MonsterSvgValidator.isSafe(svg)) return Optional.of(svg);
            LOGGER.info("Gemini monster illustration was rejected by the SVG allowlist; using fallback.");
        } catch (Exception e) {
            LOGGER.warn("Gemini monster illustration failed: exception={}", e.getClass().getName());
        }
        return Optional.empty();
    }

    @Override
    public Optional<CpuChoice> chooseCard(List<MonsterCard> hand, String apiKey) {
        // 名前・説明文などの文字列や、相手（P1）のカードは渡さない。
        JsonArray cards = new JsonArray();
        for (MonsterCard card : hand) {
            JsonObject o = new JsonObject();
            o.addProperty("id", card.id());
            o.addProperty("element", card.element());
            o.addProperty("rarity", card.rarity());
            o.addProperty("hp", card.hp());
            o.addProperty("atk", card.atk());
            o.addProperty("def", card.def());
            o.addProperty("spd", card.spd());
            o.addProperty("luck", card.luck());
            o.addProperty("skillPower", card.skillPower());
            cards.add(o);
        }
        String prompt = """
                あなたはカード対戦ゲームのコンピュータプレイヤーです。手札から対戦に使うカードを1枚選んでください。
                対戦のルール：HP・ATK・DEF・SPD・LUCKで戦う。SPDが高い側が先攻。3回目ごとの行動は必殺技（ATK×skillPower）。
                ダメージは ATK×倍率 − 相手のDEF×0.5 が基本。LUCK%%の確率で会心（1.5倍）。
                属性は 炎→風→土→雷→炎 の順に有利（1.25倍）。相手のカードは分からない。
                手札：%s
                cardId には手札のidを、reason には選んだ理由を日本語40文字以内で書いてください。
                出力は指定されたJSONスキーマだけにしてください。
                """.formatted(gson.toJson(cards));
        Schema schema = Schema.builder().type(Type.Known.OBJECT)
                .properties(new LinkedHashMap<>(Map.of(
                        "cardId", Schema.builder().type(Type.Known.INTEGER).build(),
                        "reason", Schema.builder().type(Type.Known.STRING).build())))
                .required("cardId", "reason")
                .build();
        try {
            String text = generate(apiKey, prompt, schema, CPU_TIMEOUT_MS);
            JsonObject parsed = gson.fromJson(text, JsonObject.class);
            if (parsed == null || !parsed.has("cardId") || !parsed.has("reason")) return Optional.empty();
            long cardId = parsed.get("cardId").getAsLong();
            String reason = parsed.get("reason").getAsString().replaceAll("\\p{Cntrl}", "").trim();
            if (reason.isEmpty() || reason.codePointCount(0, reason.length()) > MAX_REASON_LENGTH) return Optional.empty();
            if (hand.stream().noneMatch(card -> card.id() == cardId)) return Optional.empty();
            return Optional.of(new CpuChoice(cardId, reason));
        } catch (Exception e) {
            LOGGER.warn("Gemini CPU card choice failed: exception={}", e.getClass().getName());
            return Optional.empty();
        }
    }

    private String generate(String apiKey, String prompt, Schema schema, int timeoutMs) {
        try (Client client = Client.builder()
                .apiKey(apiKey)
                .httpOptions(HttpOptions.builder().timeout(timeoutMs).build())
                .build()) {
            GenerateContentConfig config = GenerateContentConfig.builder()
                    .responseMimeType("application/json")
                    .responseSchema(schema)
                    .candidateCount(1)
                    .build();
            GenerateContentResponse response = client.models.generateContent(settings.model(), prompt, config);
            return response.text();
        }
    }
}
