package com.example.receipt.service;

import com.example.receipt.dto.ReceiptText;
import com.example.receipt.dto.ReceiptItemData;
import com.example.receipt.dto.ReceiptStructuredData;
import com.example.receipt.exception.ReceiptException;
import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
public class GeminiReceiptAnalyzer implements ReceiptAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeminiReceiptAnalyzer.class);
    private static final int GEMINI_REQUEST_TIMEOUT_MS = 180_000;
    private static final String PROMPT = """
            この画像はレシートです。印字されている文字列を上から下へ読み取り、
            1行ごとに lines 配列へ格納し、同じ解析結果から structuredData も作成してください。
            structuredData の店舗カテゴリは スーパー、コンビニ、ドラッグストア、飲食店、その他のいずれか、
            商品カテゴリは 食料品、日用品、飲食、交通・移動、その他のいずれかにしてください。
            読み取れない値は null とし、印字されていない値を作らないでください。
            商品には購入した商品・サービスだけを含め、税・小計・合計・預り金・釣銭を含めないでください。
            推測で存在しない文字を追加しないでください。
            バーコード画像そのものは文字列化しなくて構いません。
            出力は指定されたJSONスキーマだけにしてください。
            """;

    private final String model;
    private final Gson gson = new Gson();

    public GeminiReceiptAnalyzer(
            @Value("${gemini.receipt-model:gemini-3.5-flash-lite}") String model) {
        this.model = model;
    }

    @Override
    public ReceiptText analyze(byte[] imageBytes, String mimeType, String geminiApiKey) {
        final String activeApiKey;
        try {
            activeApiKey = GeminiApiKeyPolicy.requireValid(geminiApiKey);
        } catch (IllegalArgumentException e) {
            String normalized = geminiApiKey == null ? "" : geminiApiKey.trim();
            String code = normalized.isBlank() ? "GEMINI_API_KEY_MISSING" : "INVALID_GEMINI_API_KEY";
            String message = normalized.isBlank()
                    ? "Gemini APIキーをWeb画面で入力してください。"
                    : "Gemini APIキーの形式を確認してください。";
            throw new ReceiptException(HttpStatus.BAD_REQUEST, code, message);
        }

        try (Client client = Client.builder()
                .apiKey(activeApiKey)
                .httpOptions(HttpOptions.builder().timeout(GEMINI_REQUEST_TIMEOUT_MS).build())
                .build()) {
            Map<String, Schema> properties = new LinkedHashMap<>();
            properties.put(
                    "lines",
                    Schema.builder()
                            .type(Type.Known.ARRAY)
                            .items(Schema.builder().type(Type.Known.STRING).build())
                            .build()
            );
            Schema item = Schema.builder().type(Type.Known.OBJECT).properties(new LinkedHashMap<>(Map.of(
                    "name", Schema.builder().type(Type.Known.STRING).build(),
                    "category", Schema.builder().type(Type.Known.STRING).build(),
                    "quantity", Schema.builder().type(Type.Known.NUMBER).build(),
                    "unitPrice", Schema.builder().type(Type.Known.INTEGER).build(),
                    "amount", Schema.builder().type(Type.Known.INTEGER).build()
            ))).build();
            properties.put("structuredData", Schema.builder().type(Type.Known.OBJECT)
                    .properties(new LinkedHashMap<>(Map.of(
                            "storeName", Schema.builder().type(Type.Known.STRING).build(),
                            "branchName", Schema.builder().type(Type.Known.STRING).build(),
                            "storeCategory", Schema.builder().type(Type.Known.STRING).build(),
                            "purchasedAt", Schema.builder().type(Type.Known.STRING).build(),
                            "totalAmount", Schema.builder().type(Type.Known.INTEGER).build(),
                            "paymentMethod", Schema.builder().type(Type.Known.STRING).build(),
                            "receiptNumber", Schema.builder().type(Type.Known.STRING).build(),
                            "items", Schema.builder().type(Type.Known.ARRAY).items(item).build()
                    ))).build());

            Schema responseSchema = Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(properties)
                    .required("lines")
                    .build();

            GenerateContentConfig config = GenerateContentConfig.builder()
                    .responseMimeType("application/json")
                    .responseSchema(responseSchema)
                    .candidateCount(1)
                    .build();

            Content content = Content.fromParts(
                    Part.fromBytes(imageBytes, mimeType),
                    Part.fromText(PROMPT)
            );

            GenerateContentResponse response = client.models.generateContent(model, content, config);
            String responseText = response.text();
            if (responseText == null || responseText.isBlank()) {
                throw new ReceiptException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "EMPTY_ANALYSIS_RESULT",
                        "Geminiから解析結果を取得できませんでした。"
                );
            }

            GeminiResponse parsed = gson.fromJson(responseText, GeminiResponse.class);
            if (parsed == null || parsed.lines() == null) {
                throw new ReceiptException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "INVALID_ANALYSIS_RESULT",
                        "Geminiの解析結果が想定形式ではありません。"
                );
            }

            List<String> normalizedLines = parsed.lines().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .limit(1000)
                    .toList();

            if (normalizedLines.isEmpty()) {
                throw new ReceiptException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "NO_TEXT_FOUND",
                        "レシートから文字を読み取れませんでした。"
                );
            }

            return new ReceiptText(normalizedLines, null, toStructuredData(parsed.structuredData()));
        } catch (ReceiptException e) {
            throw e;
        } catch (ApiException e) {
            throw mapApiException(e);
        } catch (Exception e) {
            LOGGER.error("Gemini receipt analysis failed: model={}, mimeType={}, imageBytes={}, exception={}, message={}",
                    model, mimeType, imageBytes.length, e.getClass().getName(), e.getMessage());
            ReceiptException classified = classifyUnexpectedGeminiFailure(e);
            if (classified != null) throw classified;
            throw new ReceiptException(
                    HttpStatus.BAD_GATEWAY,
                    "GEMINI_API_ERROR",
                    "Gemini APIでレシート解析に失敗しました。"
            );
        }
    }

    /**
     * SDK versions can surface transport/proxy failures as a plain runtime
     * exception instead of ApiException.  Preserve the actionable response
     * for quota and authentication failures in that case as well.
     */
    static ReceiptException classifyUnexpectedGeminiFailure(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("429") || normalized.contains("resource_exhausted")
                || normalized.contains("resource exhausted") || normalized.contains("quota")) {
            return new ReceiptException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "GEMINI_QUOTA_EXCEEDED",
                    "Gemini APIの利用上限に達しました。別の利用可能なAPIキーを入力して再試行してください。"
            );
        }
        if (normalized.contains("401") || normalized.contains("403")
                || normalized.contains("api key") || normalized.contains("apikey")
                || normalized.contains("permission denied") || normalized.contains("unauthorized")) {
            return new ReceiptException(
                    HttpStatus.UNAUTHORIZED,
                    "GEMINI_API_KEY_REJECTED",
                    "Gemini APIキーが無効、ブロック済み、または権限不足です。別のAPIキーを入力してください。"
            );
        }
        return null;
    }

    static ReceiptException mapApiException(ApiException e) {
        String apiStatus = e.status() == null ? "" : e.status();
        String apiMessage = e.message() == null ? "" : e.message();
        String normalizedMessage = apiMessage.toLowerCase(java.util.Locale.ROOT);
        LOGGER.warn("Gemini API rejected request: code={}, status={}, message={}",
                e.code(), apiStatus, apiMessage);

        if (e.code() == 429 || "RESOURCE_EXHAUSTED".equalsIgnoreCase(e.status())) {
            return new ReceiptException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "GEMINI_QUOTA_EXCEEDED",
                    "Gemini APIの利用上限に達しました。少し待って再試行するか、別の利用可能なAPIキーを入力して再試行してください。"
            );
        }

        if (e.code() == 401 || e.code() == 403
                || normalizedMessage.contains("api key")
                || normalizedMessage.contains("apikey")
                || normalizedMessage.contains("api_key")) {
            return new ReceiptException(
                    HttpStatus.UNAUTHORIZED,
                    "GEMINI_API_KEY_REJECTED",
                    "Gemini APIキーが無効、ブロック済み、または権限不足です。別のAPIキーを入力してください。"
            );
        }

        return new ReceiptException(
                e.code() >= 400 && e.code() < 500 ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY,
                "GEMINI_API_ERROR",
                e.code() >= 400 && e.code() < 500
                        ? "Gemini APIへのリクエストが拒否されました。APIキー、モデル、画像形式を確認してください。"
                        : "Gemini APIでレシート解析に失敗しました。"
        );
    }

    private ReceiptStructuredData toStructuredData(RawStructuredData raw) {
        if (raw == null) return null;
        LocalDateTime purchasedAt = null;
        if (raw.purchasedAt() != null && !raw.purchasedAt().isBlank()) {
            try { purchasedAt = LocalDateTime.parse(raw.purchasedAt()); } catch (RuntimeException ignored) { }
        }
        List<ReceiptItemData> items = raw.items() == null ? List.of() : raw.items().stream()
                .filter(Objects::nonNull)
                .map(item -> new ReceiptItemData(item.name(), item.category(), item.quantity(), item.unitPrice(), item.amount()))
                .toList();
        return new ReceiptStructuredData(raw.storeName(), raw.branchName(), raw.storeCategory(), purchasedAt,
                raw.totalAmount(), raw.paymentMethod(), raw.receiptNumber(), items);
    }

    private record GeminiResponse(List<String> lines, RawStructuredData structuredData) { }
    private record RawStructuredData(String storeName, String branchName, String storeCategory, String purchasedAt,
                                     Long totalAmount, String paymentMethod, String receiptNumber, List<RawItem> items) { }
    private record RawItem(String name, String category, BigDecimal quantity, Long unitPrice, Long amount) { }
}
