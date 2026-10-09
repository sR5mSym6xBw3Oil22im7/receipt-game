package com.example.receipt.service;

import com.example.receipt.config.GeminiApiKeys;
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

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

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
            purchasedAt は yyyy-MM-ddTHH:mm:ss 形式（例 2026-08-03T10:53:00）で出力してください。
            秒が印字されていない場合は 00 としてください。
            textRotation には、レシートの文字を正しい向き（上から下へ横書きで読める向き）にするために
            画像を時計回りに何度回転させる必要があるかを 0、90、180、270 のいずれかで出力してください。
            商品には購入した商品・サービスだけを含め、税・小計・合計・預り金・釣銭を含めないでください。
            推測で存在しない文字を追加しないでください。
            お客様の氏名、電話番号、会員番号・ポイントカード番号、住所、メールアドレス、クレジットカード番号は
            structuredData に出力しないでください。該当する箇所は空にしてください。
            店舗の電話番号・住所も structuredData には含めないでください。
            バーコード画像そのものは文字列化しなくて構いません。
            出力は指定されたJSONスキーマだけにしてください。
            """;

    private final String model;
    private final String apiKey;
    private final Gson gson = new Gson();

    public GeminiReceiptAnalyzer(
            @Value("${gemini.receipt-model:gemini-3.5-flash-lite}") String model,
            GeminiApiKeys apiKeys) {
        this.model = model;
        this.apiKey = apiKeys.analyze();
    }

    @Override
    public ReceiptText analyze(byte[] imageBytes, String mimeType) {
        return analyze(imageBytes, mimeType, apiKey);
    }

    /** 用途別のキーで解析する（モンスターレシート対戦の画像カードは GEMINI_API_PLAYER1／PLAYER2 を使う）。 */
    public ReceiptText analyze(byte[] imageBytes, String mimeType, String apiKey) {
        final String activeApiKey;
        try {
            activeApiKey = GeminiApiKeyPolicy.requireValid(apiKey);
        } catch (IllegalArgumentException e) {
            String normalized = apiKey == null ? "" : apiKey.trim();
            String code = normalized.isBlank() ? "GEMINI_API_KEY_MISSING" : "INVALID_GEMINI_API_KEY";
            String message = normalized.isBlank()
                    ? "Gemini APIキーが設定されていません。.envのGEMINI_API_ANALYZEまたはGEMINI_API_DEFAULTを設定してください。"
                    : "Gemini APIキーの形式を確認してください。.envのGEMINI_API_ANALYZEまたはGEMINI_API_DEFAULTを確認してください。";
            throw new ReceiptException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
        }

        try (Client client = Client.builder()
                .apiKey(activeApiKey)
                .httpOptions(HttpOptions.builder().timeout(GEMINI_REQUEST_TIMEOUT_MS).build())
                .build()) {
            GenerateContentConfig config = GenerateContentConfig.builder()
                    .responseMimeType("application/json")
                    .responseSchema(responseSchema())
                    .candidateCount(1)
                    .build();

            Attempt best = request(client, config, imageBytes, mimeType);
            // 横向き・逆さまに撮られた画像は文字を正しい向きに回して読み直し、読み取れた行が多い方を使う。
            // 回転角はGeminiの推定なので外れることがあるが、行数で比べるため元の結果より悪くはならない。
            int rotation = best.rotation();
            if (rotation == 90 || rotation == 180 || rotation == 270) {
                byte[] rotated = rotateClockwise(imageBytes, mimeType, rotation);
                if (rotated != null) {
                    try {
                        Attempt retried = request(client, config, rotated, mimeType);
                        if (retried.lines().size() > best.lines().size()) best = retried;
                    } catch (ReceiptException e) {
                        LOGGER.warn("Rotated receipt re-analysis failed: rotation={}, code={}", rotation, e.code());
                    }
                }
            }

            if (best.lines().isEmpty()) {
                throw new ReceiptException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "NO_TEXT_FOUND",
                        "レシートから文字を読み取れませんでした。"
                );
            }

            return new ReceiptText(best.lines(), null, toStructuredData(best.structuredData(), best.lines()));
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
     * 構造化データの各項目は必須（値がなければnull）にする。
     * 任意項目にするとGeminiが店名と合計以外を省略し、商品・購入日時が保存されなくなるため。
     */
    private static Schema responseSchema() {
        Schema item = Schema.builder().type(Type.Known.OBJECT).properties(new LinkedHashMap<>(Map.of(
                "name", nullable(Type.Known.STRING),
                "category", nullable(Type.Known.STRING),
                "quantity", nullable(Type.Known.NUMBER),
                "unitPrice", nullable(Type.Known.INTEGER),
                "amount", nullable(Type.Known.INTEGER)
        ))).required("name", "category", "quantity", "unitPrice", "amount").build();
        Schema structuredData = Schema.builder().type(Type.Known.OBJECT)
                .properties(new LinkedHashMap<>(Map.of(
                        "storeName", nullable(Type.Known.STRING),
                        "branchName", nullable(Type.Known.STRING),
                        "storeCategory", nullable(Type.Known.STRING),
                        "purchasedAt", nullable(Type.Known.STRING),
                        "totalAmount", nullable(Type.Known.INTEGER),
                        "paymentMethod", nullable(Type.Known.STRING),
                        "receiptNumber", nullable(Type.Known.STRING),
                        "items", Schema.builder().type(Type.Known.ARRAY).items(item).build()
                )))
                .required("storeName", "branchName", "storeCategory", "purchasedAt", "totalAmount",
                        "paymentMethod", "receiptNumber", "items")
                .build();

        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("lines", Schema.builder()
                .type(Type.Known.ARRAY)
                .items(Schema.builder().type(Type.Known.STRING).build())
                .build());
        properties.put("structuredData", structuredData);
        properties.put("textRotation", Schema.builder().type(Type.Known.INTEGER).build());
        return Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(properties)
                .required("lines", "structuredData", "textRotation")
                .build();
    }

    private static Schema nullable(Type.Known type) {
        return Schema.builder().type(type).nullable(true).build();
    }

    private Attempt request(Client client, GenerateContentConfig config, byte[] imageBytes, String mimeType) {
        Content content = Content.fromParts(
                Part.fromBytes(imageBytes, mimeType),
                Part.fromText(PROMPT + yearHint(LocalDate.now()))
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
        int rotation = parsed.textRotation() == null ? 0 : Math.floorMod(parsed.textRotation(), 360);
        return new Attempt(normalizedLines, parsed.structuredData(), rotation);
    }

    /** 画像を時計回りに90度単位で回す。読み込めない形式ならnull（回転せずに元の結果を使う）。 */
    static byte[] rotateClockwise(byte[] imageBytes, String mimeType, int degrees) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (source == null) return null;
            int quadrants = Math.floorMod(degrees / 90, 4);
            int width = source.getWidth();
            int height = source.getHeight();
            boolean swap = quadrants % 2 == 1;
            BufferedImage target = new BufferedImage(swap ? height : width, swap ? width : height, BufferedImage.TYPE_INT_RGB);
            AffineTransform transform = new AffineTransform();
            switch (quadrants) {
                case 1 -> transform.translate(height, 0);
                case 2 -> transform.translate(width, height);
                case 3 -> transform.translate(0, width);
                default -> { }
            }
            transform.quadrantRotate(quadrants);
            Graphics2D graphics = target.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, target.getWidth(), target.getHeight());
                graphics.drawImage(source, transform, null);
            } finally {
                graphics.dispose();
            }
            return encode(target, "image/png".equalsIgnoreCase(mimeType) ? "png" : "jpeg");
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Receipt image rotation failed: degrees={}, exception={}", degrees, e.getClass().getName());
            return null;
        }
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName(format).next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if ("jpeg".equals(format)) {
                // 小さな文字が潰れないよう、既定（0.75）より高い画質で書き出す
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.92f);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
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
                    "Gemini APIの利用上限に達しました。少し待って再試行してください。"
            );
        }
        if (normalized.contains("401") || normalized.contains("403")
                || normalized.contains("api key") || normalized.contains("apikey")
                || normalized.contains("permission denied") || normalized.contains("unauthorized")) {
            return new ReceiptException(
                    HttpStatus.UNAUTHORIZED,
                    "GEMINI_API_KEY_REJECTED",
                    "Gemini APIキーが無効、ブロック済み、または権限不足です。.envのGEMINI_API_ANALYZEまたはGEMINI_API_DEFAULTを確認してください。"
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
                    "Gemini APIの利用上限に達しました。少し待って再試行してください。"
            );
        }

        if (e.code() == 401 || e.code() == 403
                || normalizedMessage.contains("api key")
                || normalizedMessage.contains("apikey")
                || normalizedMessage.contains("api_key")) {
            return new ReceiptException(
                    HttpStatus.UNAUTHORIZED,
                    "GEMINI_API_KEY_REJECTED",
                    "Gemini APIキーが無効、ブロック済み、または権限不足です。.envのGEMINI_API_ANALYZEまたはGEMINI_API_DEFAULTを確認してください。"
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

    private ReceiptStructuredData toStructuredData(RawStructuredData raw, List<String> lines) {
        if (raw == null) return null;
        LocalDateTime purchasedAt = correctYearByWeekday(
                parsePurchasedAt(raw.purchasedAt()), lines, LocalDate.now());
        List<ReceiptItemData> items = raw.items() == null ? List.of() : raw.items().stream()
                .filter(Objects::nonNull)
                .map(item -> new ReceiptItemData(item.name(), item.category(), item.quantity(), item.unitPrice(), item.amount()))
                .toList();
        return new ReceiptStructuredData(raw.storeName(), raw.branchName(), raw.storeCategory(), purchasedAt,
                raw.totalAmount(), raw.paymentMethod(), raw.receiptNumber(), items);
    }

    /**
     * 購入日時を読み取る。指定した形式（2026-08-03T10:53:00）以外に、
     * レシートの印字に近い形（2026/8/3 10:53）・タイムゾーン付き・日付だけの値も受け付ける。
     */
    static LocalDateTime parsePurchasedAt(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        try { return LocalDateTime.parse(text); } catch (RuntimeException ignored) { }
        try { return OffsetDateTime.parse(text).toLocalDateTime(); } catch (RuntimeException ignored) { }
        String normalized = text.replace('/', '-').replace('T', ' ').replaceAll("\\s+", " ");
        for (DateTimeFormatter formatter : PURCHASED_AT_FORMATS) {
            try { return LocalDateTime.parse(normalized, formatter); } catch (RuntimeException ignored) { }
        }
        try { return LocalDate.parse(normalized, DateTimeFormatter.ofPattern("uuuu-M-d")).atStartOfDay(); }
        catch (RuntimeException ignored) { }
        return null;
    }

    private static final java.util.regex.Pattern PRINTED_WEEKDAY = java.util.regex.Pattern.compile(
            "(\\d{1,2})\\s*[/月.]\\s*(\\d{1,2})\\s*日?\\s*[(（]\\s*([日月火水木金土])\\s*[)）]");
    private static final String WEEKDAYS = "月火水木金土日";
    private static final int WEEKDAY_YEAR_SEARCH_RANGE = 10;

    /** 年の印字がかすれている場合に、Geminiが古い年を推測しないよう今日の日付を伝える。 */
    static String yearHint(LocalDate today) {
        return "今日の日付は " + today + " です。購入日時の年がかすれて判読できない場合は、"
                + "印字された月日・曜日と一致し、今日以前で最も近い年としてください。\n";
    }

    /**
     * 年の印字がかすれていると、Geminiが年を推測して誤ることがある（例：2026年を2017年や2020年と読む）。
     * 同じ月日の曜日が印字されていれば、その曜日と一致し、今日以前で最も近い年に合わせる。
     * 曜日は6年や11年ごとに同じ並びになるため、曜日が合っていても古い年は直す
     * （レシートは最近のものを取り込む前提）。曜日が読み取れない、または該当する年がない場合はそのまま返す。
     */
    static LocalDateTime correctYearByWeekday(LocalDateTime purchasedAt, List<String> lines, LocalDate today) {
        if (purchasedAt == null || lines == null) return purchasedAt;
        for (String line : lines) {
            if (line == null) continue;
            java.util.regex.Matcher matcher = PRINTED_WEEKDAY.matcher(line);
            while (matcher.find()) {
                int month = Integer.parseInt(matcher.group(1));
                int day = Integer.parseInt(matcher.group(2));
                if (month != purchasedAt.getMonthValue() || day != purchasedAt.getDayOfMonth()) continue;
                java.time.DayOfWeek printed = java.time.DayOfWeek.of(WEEKDAYS.indexOf(matcher.group(3)) + 1);
                for (int year = today.getYear(); year > today.getYear() - WEEKDAY_YEAR_SEARCH_RANGE; year--) {
                    LocalDate candidate;
                    try {
                        candidate = LocalDate.of(year, month, day);
                    } catch (RuntimeException e) {
                        continue;
                    }
                    if (!candidate.isAfter(today) && candidate.getDayOfWeek() == printed) {
                        return purchasedAt.withYear(year);
                    }
                }
                return purchasedAt;
            }
        }
        return purchasedAt;
    }

    private static final List<DateTimeFormatter> PURCHASED_AT_FORMATS = List.of(
            DateTimeFormatter.ofPattern("uuuu-M-d H:mm:ss"),
            DateTimeFormatter.ofPattern("uuuu-M-d H:mm")
    );

    private record Attempt(List<String> lines, RawStructuredData structuredData, int rotation) { }
    private record GeminiResponse(List<String> lines, RawStructuredData structuredData, Integer textRotation) { }
    private record RawStructuredData(String storeName, String branchName, String storeCategory, String purchasedAt,
                                     Long totalAmount, String paymentMethod, String receiptNumber, List<RawItem> items) { }
    private record RawItem(String name, String category, BigDecimal quantity, Long unitPrice, Long amount) { }
}
