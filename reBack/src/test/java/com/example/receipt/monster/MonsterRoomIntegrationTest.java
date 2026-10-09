package com.example.receipt.monster;

import com.example.receipt.dto.ReceiptItemData;
import com.example.receipt.dto.ReceiptStructuredData;
import com.example.receipt.dto.ReceiptText;
import com.example.receipt.monster.engine.MonsterCard;
import com.example.receipt.monster.engine.MonsterCardGenerator;
import com.example.receipt.monster.engine.MonsterParameters;
import com.example.receipt.repository.ReceiptTableRepository;
import com.example.receipt.service.ReceiptService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.monster.ai-illustration-enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MonsterRoomIntegrationTest {
    private static final AtomicInteger IP = new AtomicInteger(1);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReceiptService receiptService;
    @Autowired ReceiptTableRepository receiptRepository;
    @Autowired MonsterCardRepository cardRepository;
    @Autowired MonsterCardService cardService;
    @Autowired StubMonsterAi ai;

    private String ip;

    @BeforeEach
    void setUp() {
        ip = "10.0.0." + IP.getAndIncrement();
        ai.reset();
        jdbc.update("DELETE FROM monster_room");
        jdbc.update("DELETE FROM monster_card");
        for (String table : jdbc.queryForList("SELECT LOWER(table_name) FROM information_schema.tables "
                + "WHERE LOWER(table_schema) = 'public' AND LOWER(table_name) LIKE 'receipt_%'", String.class)) {
            if (table.matches("receipt_[0-9a-f]{32}")) jdbc.execute("DROP TABLE " + table);
        }
        if (cardRepository.tableExists("receipt_structured_item")) jdbc.update("DELETE FROM receipt_structured_item");
        if (cardRepository.tableExists("receipt_structured_summary")) jdbc.update("DELETE FROM receipt_structured_summary");
        if (cardRepository.tableExists("receipt_image_hash_registry")) jdbc.update("DELETE FROM receipt_image_hash_registry");
    }

    @AfterEach
    void tearDown() {
        cardService.setSanitizerForTest(null);
    }

    // ---------- 解析・保存・削除との連動（10章）と個人情報（9章） ----------

    @Test
    void savingActivatesCardAndRemovesPersonalInfo() {
        String table = saveReceipt(0, "山田太郎様", List.of("TEL 03-1234-5678", "会員番号 4000100123", "米 10kg ¥12,400"));

        assertThat(cardRepository.activeCardIds()).hasSize(1);
        String stored = String.join("|", jdbc.queryForList(
                "SELECT store_name FROM receipt_structured_summary WHERE receipt_table_name = ?", String.class, table))
                + String.join("|", jdbc.queryForList("SELECT item_name FROM receipt_structured_item", String.class))
                + String.join("|", jdbc.queryForList("SELECT text FROM " + table, String.class));
        assertThat(stored).doesNotContain("山田", "03-1234-5678", "4000100123").contains("米 10kg", "¥12,400");

        receiptRepository.deleteReceipt(table);
        assertThat(cardRepository.countAll()).isZero();
    }

    @Test
    void savingCreatesCardWithMonsterKeyParametersAndIllustration() {
        ai.illustrationSvg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 200\"><circle cx=\"100\" cy=\"100\" r=\"50\"/></svg>";
        // 範囲外の値・長すぎる技名は、サーバー側で範囲に収める／計算式の値を使う
        ai.parameters = new MonsterParameters("コメドラ", "雷", 9999, 120, 1, 70, 20, "とても長すぎる必殺技の名前です", 3.0, "米から生まれた雷の魔物。");

        String table = saveReceipt(0, "テスト店", List.of("米 10kg ¥12,400"));

        assertThat(ai.parameterKeys).containsExactly("monster-key");
        assertThat(ai.illustrateKeys).containsExactly("monster-key");
        MonsterCard card = cardRepository.findById(cardRepository.activeCardIds().getFirst()).orElseThrow().card();
        assertThat(card.name()).isEqualTo("コメドラ");
        assertThat(card.element()).isEqualTo("雷");
        assertThat(card.hp()).isEqualTo(770); // 上限700 ＋ スーパーのボーナス10%
        assertThat(card.def()).isEqualTo(MonsterCardGenerator.DEF_MIN);
        assertThat(card.skillPower()).isEqualTo(2.0);
        assertThat(card.skillName()).isNotEqualTo("とても長すぎる必殺技の名前です");
        assertThat(card.flavor()).isEqualTo("米から生まれた雷の魔物。");
        assertThat(card.power()).isEqualTo((int) Math.floor(card.hp() / 5.0 + card.atk() + card.def() + card.spd() / 2.0 + card.luck() * 2));
        assertThat(card.svg()).isEqualTo(ai.illustrationSvg);
        assertThat(jdbc.queryForObject("SELECT receipt_table_name FROM monster_card WHERE id = ?", String.class, card.id()))
                .isEqualTo(table);
    }

    @Test
    void savingOverwritesDraftWithMonsterKeyCard() {
        String sha = reserve();
        ReceiptStructuredData data = new ReceiptStructuredData("テスト店", null, "スーパー", LocalDateTime.parse("2026-10-01T10:20:00"),
                800L, null, null, List.of(new ReceiptItemData("米 10kg", "食料品", BigDecimal.ONE, 800L, 800L)));
        cardRepository.insertDraft(MonsterCardGenerator.generate(data, sha, 10, "ANALYZE"));
        ai.parameters = new MonsterParameters("コメドラ", "雷", 300, 60, 40, 50, 10, "稲妻米", 1.6, "米の魔物。");

        receiptService.store(List.of("米 10kg ¥800"), sha, data);

        MonsterCardRepository.StoredCard stored = cardRepository.findBySha(sha).orElseThrow();
        assertThat(stored.status()).isEqualTo(MonsterCardRepository.ACTIVE);
        assertThat(stored.card().name()).isEqualTo("コメドラ");
        assertThat(ai.parameterKeys).containsExactly("monster-key");
    }

    @Test
    void saveIsRejectedWhenPersonalInfoCannotBeRemoved() throws Exception {
        cardService.setSanitizerForTest((data, lines) -> {
            throw new IllegalStateException("simulated failure");
        });
        String sha = reserve();

        MvcResult result = mockMvc.perform(post("/api/receipts/save")
                        .with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"lines\":[\"TEST\"],\"sha256\":\"" + sha + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains(MonsterCardService.PII_FAILED_MESSAGE);
        assertThat(cardRepository.countAll()).isZero();
        assertThat(jdbc.queryForObject("SELECT table_name FROM receipt_image_hash_registry WHERE image_sha256 = ?",
                String.class, sha)).isNull();
    }

    // ---------- 2人対戦 ----------

    @Test
    void twoPlayersPickCardsAndBattleWithoutSeeingEachOther() throws Exception {
        seed(12);
        JsonObject p1 = create();
        String code = p1.get("code").getAsString();
        JsonObject p2 = json(mockMvc.perform(post("/api/monster/rooms/" + code + "/join").with(fromIp()))
                .andExpect(status().isOk()).andReturn());
        String t1 = p1.get("token").getAsString(), t2 = p2.get("token").getAsString();
        assertThat(p2.get("seat").getAsString()).isEqualTo("P2");
        assertThat(p2.getAsJsonObject("room").get("status").getAsString()).isEqualTo("SELECTING");

        // 3人目は参加できない
        mockMvc.perform(post("/api/monster/rooms/" + code + "/join").with(fromIp())).andExpect(status().isConflict());
        // 席トークンがなければ操作できない
        mockMvc.perform(get("/api/monster/rooms/" + code + "/field")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/monster/rooms/" + code + "/field").header("X-Seat-Token", "wrong")).andExpect(status().isForbidden());

        JsonObject f1 = getJson("/api/monster/rooms/" + code + "/field", t1);
        JsonObject f2 = getJson("/api/monster/rooms/" + code + "/field", t2);
        assertThat(f1.getAsJsonArray("receipts")).hasSize(6);
        assertThat(f2.getAsJsonArray("receipts")).hasSize(6);
        assertThat(f1.get("shuffleVisible").getAsBoolean()).isTrue();
        // レシートの応答に、カードの中身・行テキスト・画像ハッシュ・テーブル名は含めない
        String fieldText = f1.toString();
        assertThat(fieldText).doesNotContain("svg", "power", "sha", "receipt_", "lines", "TEL");
        assertThat(storeNames(f1)).doesNotContainAnyElementsOf(storeNames(f2));

        // シャッフルはレシートを選ぶ前だけ
        postJson("/api/monster/rooms/" + code + "/shuffle", t1, "").andExpect(status().isOk());
        JsonObject flip = json(postJson("/api/monster/rooms/" + code + "/flip", t1, "{\"index\":0}").andExpect(status().isOk()).andReturn());
        assertThat(flip.getAsJsonObject("card").get("svg").getAsString()).startsWith("<svg");
        assertThat(flip.getAsJsonObject("field").get("canShuffle").getAsBoolean()).isFalse();
        postJson("/api/monster/rooms/" + code + "/shuffle", t1, "").andExpect(status().isConflict());
        postJson("/api/monster/rooms/" + code + "/flip", t1, "{\"index\":0}").andExpect(status().isConflict());
        postJson("/api/monster/rooms/" + code + "/flip", t1, "{\"index\":1}").andExpect(status().isOk());
        postJson("/api/monster/rooms/" + code + "/flip", t1, "{\"index\":2}").andExpect(status().isOk());
        // 4枚目はサーバーが拒否する
        postJson("/api/monster/rooms/" + code + "/flip", t1, "{\"index\":3}").andExpect(status().isConflict());
        JsonObject full = getJson("/api/monster/rooms/" + code + "/field", t1);
        assertThat(full.getAsJsonArray("hand")).hasSize(3);

        // 手札以外のカードは決定できない
        long notMine = f2.getAsJsonArray("receipts").size() > 0 ? cardIdOfOtherSeat(code) : -1;
        postJson("/api/monster/rooms/" + code + "/select", t1, "{\"cardId\":" + notMine + "}").andExpect(status().isBadRequest());
        long myCard = full.getAsJsonArray("hand").get(1).getAsJsonObject().get("id").getAsLong();
        JsonObject afterSelect = json(postJson("/api/monster/rooms/" + code + "/select", t1, "{\"cardId\":" + myCard + "}")
                .andExpect(status().isOk()).andReturn());
        assertThat(afterSelect.get("youConfirmed").getAsBoolean()).isTrue();
        postJson("/api/monster/rooms/" + code + "/select", t1, "{\"cardId\":" + myCard + "}").andExpect(status().isConflict());

        // 相手から見えるのは「確定済み」の状態だけ
        JsonObject seenByP2 = getJson("/api/monster/rooms/" + code, t2);
        assertThat(seenByP2.get("opponentState").getAsString()).isEqualTo("CONFIRMED");
        assertThat(seenByP2.toString()).doesNotContain(String.valueOf(myCard));
        mockMvc.perform(get("/api/monster/rooms/" + code + "/battle").header("X-Seat-Token", t2)).andExpect(status().isConflict());

        JsonObject flip2 = json(postJson("/api/monster/rooms/" + code + "/flip", t2, "{\"index\":0}").andReturn());
        long p2Card = flip2.getAsJsonObject("card").get("id").getAsLong();
        JsonObject started = json(postJson("/api/monster/rooms/" + code + "/select", t2, "{\"cardId\":" + p2Card + "}").andReturn());
        assertThat(started.get("battleReady").getAsBoolean()).isTrue();

        JsonObject b1 = getJson("/api/monster/rooms/" + code + "/battle", t1);
        JsonObject b2 = getJson("/api/monster/rooms/" + code + "/battle", t2);
        assertThat(b1).isEqualTo(b2);
        assertThat(b1.getAsJsonObject("cards").getAsJsonObject("P1").get("id").getAsLong()).isEqualTo(myCard);
        assertThat(b1.getAsJsonObject("cards").getAsJsonObject("P2").get("id").getAsLong()).isEqualTo(p2Card);
        assertThat(b1.getAsJsonObject("receipts").getAsJsonObject("P1").get("storeName").getAsString()).startsWith("店");
        assertThat(b1.get("winner").getAsString()).isIn("P1", "P2", "DRAW");
        assertThat(b1.getAsJsonArray("log")).isNotEmpty();

        // 再戦：配り直し、手札・選択をリセットする
        int round = b1.get("round").getAsInt();
        JsonObject re = json(postJson("/api/monster/rooms/" + code + "/rematch", t2, "{\"round\":" + round + "}").andReturn());
        assertThat(re.get("round").getAsInt()).isEqualTo(round + 1);
        assertThat(re.get("status").getAsString()).isEqualTo("SELECTING");
        // もう一方が同じ再戦を押しても、二重には進まない
        JsonObject re2 = json(postJson("/api/monster/rooms/" + code + "/rematch", t1, "{\"round\":" + round + "}").andReturn());
        assertThat(re2.get("round").getAsInt()).isEqualTo(round + 1);
        assertThat(getJson("/api/monster/rooms/" + code + "/field", t1).getAsJsonArray("hand")).isEmpty();

        // ロビーへ：ルームを閉じる
        mockMvc.perform(post("/api/monster/rooms/" + code + "/leave").header("X-Seat-Token", t1)).andExpect(status().isNoContent());
        JsonObject closed = getJson("/api/monster/rooms/" + code, t2);
        assertThat(closed.get("status").getAsString()).isEqualTo("CLOSED");
        assertThat(closed.get("notice").getAsString()).contains("相手がルームを閉じました");
    }

    @Test
    void fewerThanTenCardsShowsAllWithoutShuffle() throws Exception {
        seed(9);
        JsonObject p1 = create();
        String code = p1.get("code").getAsString();
        expireWait(code);
        JsonObject field = getJson("/api/monster/rooms/" + code + "/field", p1.get("token").getAsString());
        // コンピュータに1枚、あなたに残り全部
        assertThat(field.getAsJsonArray("receipts")).hasSize(8);
        assertThat(field.get("shuffleVisible").getAsBoolean()).isFalse();
        postJson("/api/monster/rooms/" + code + "/shuffle", p1.get("token").getAsString(), "").andExpect(status().isConflict());
    }

    @Test
    void notEnoughCardsClosesTheRoom() throws Exception {
        seed(1);
        JsonObject p1 = create();
        String code = p1.get("code").getAsString();
        expireWait(code);
        JsonObject state = getJson("/api/monster/rooms/" + code, p1.get("token").getAsString());
        assertThat(state.get("status").getAsString()).isEqualTo("CLOSED");
        assertThat(state.get("notice").getAsString()).contains("2枚以上");
    }

    // ---------- コンピュータ対戦（8章） ----------

    @Test
    void computerJoinsAfterTimeoutAndUsesOnlyExistingCards() throws Exception {
        seed(12);
        long before = cardRepository.countAll();
        JsonObject p1 = create();
        String code = p1.get("code").getAsString(), t1 = p1.get("token").getAsString();
        assertThat(p1.getAsJsonObject("room").get("waitSecondsLeft").getAsLong()).isPositive();
        expireWait(code);

        JsonObject state = getJson("/api/monster/rooms/" + code, t1);
        assertThat(state.get("mode").getAsString()).isEqualTo("CPU");
        assertThat(state.get("status").getAsString()).isEqualTo("SELECTING");
        mockMvc.perform(post("/api/monster/rooms/" + code + "/join").with(fromIp())).andExpect(status().isConflict());

        JsonObject flip = json(postJson("/api/monster/rooms/" + code + "/flip", t1, "{\"index\":4}").andReturn());
        long mine = flip.getAsJsonObject("card").get("id").getAsLong();
        postJson("/api/monster/rooms/" + code + "/select", t1, "{\"cardId\":" + mine + "}").andExpect(status().isOk());

        JsonObject battle = waitForBattle(code, t1);
        assertThat(battle.get("mode").getAsString()).isEqualTo("CPU");
        assertThat(battle.get("cpuReason").getAsString()).isEqualTo("テストで選びました");
        assertThat(ai.cpuHandSizes).hasSize(1);
        assertThat(ai.cpuHandSizes.getFirst()).isBetween(1, 3);
        assertThat(cardRepository.countAll()).isEqualTo(before);
    }

    @Test
    void computerFallsBackToHighestPowerWhenAiFails() throws Exception {
        seed(12);
        ai.cpuFails = true;
        JsonObject p1 = create();
        String code = p1.get("code").getAsString(), t1 = p1.get("token").getAsString();
        expireWait(code);
        JsonObject flip = json(postJson("/api/monster/rooms/" + code + "/flip", t1, "{\"index\":0}").andReturn());
        postJson("/api/monster/rooms/" + code + "/select", t1,
                "{\"cardId\":" + flip.getAsJsonObject("card").get("id").getAsLong() + "}").andExpect(status().isOk());
        JsonObject battle = waitForBattle(code, t1);
        assertThat(battle.get("cpuReason").getAsString()).isEqualTo(MonsterRoomService.CPU_FALLBACK_REASON);
        int cpuPower = battle.getAsJsonObject("cards").getAsJsonObject("P2").get("power").getAsInt();
        assertThat(cpuPower).isEqualTo(ai.lastCpuHandMaxPower);
    }

    // ---------- 画像カード（MR-10・MR-11） ----------

    @Test
    void imageCardIsCreatedOnceAndCanBeSelected() throws Exception {
        seed(12);
        JsonObject p1 = create();
        String code = p1.get("code").getAsString(), t1 = p1.get("token").getAsString();
        expireWait(code);
        long before = cardRepository.countAll();

        byte[] png = png("image-a");
        JsonObject made = json(mockMvc.perform(multipart("/api/monster/rooms/" + code + "/image-card")
                        .file(new MockMultipartFile("file", "a.txt", "text/plain", png)).header("X-Seat-Token", t1).with(fromIp()))
                .andExpect(status().isOk()).andReturn());
        assertThat(made.get("reused").getAsBoolean()).isFalse();
        assertThat(made.getAsJsonObject("card").get("source").getAsString()).isEqualTo("PLAYER1");
        assertThat(ai.readKeys).containsExactly("player1-key");
        assertThat(cardRepository.countAll()).isEqualTo(before + 1);
        // 個人情報は取り除いてから使う
        assertThat(made.toString()).doesNotContain("山田");

        // 同じ画像なら新しく作らない
        JsonObject again = json(mockMvc.perform(multipart("/api/monster/rooms/" + code + "/image-card")
                        .file(new MockMultipartFile("file", "a.png", "image/png", png)).header("X-Seat-Token", t1).with(fromIp()))
                .andExpect(status().isOk()).andReturn());
        assertThat(again.getAsJsonObject("card").get("id")).isEqualTo(made.getAsJsonObject("card").get("id"));
        assertThat(cardRepository.countAll()).isEqualTo(before + 1);
        // 手札の3枚には数えない
        assertThat(again.getAsJsonObject("field").getAsJsonArray("hand")).isEmpty();

        long imageCard = made.getAsJsonObject("card").get("id").getAsLong();
        postJson("/api/monster/rooms/" + code + "/select", t1, "{\"cardId\":" + imageCard + "}").andExpect(status().isOk());
        JsonObject battle = waitForBattle(code, t1);
        assertThat(battle.getAsJsonObject("cards").getAsJsonObject("P1").get("id").getAsLong()).isEqualTo(imageCard);
        assertThat(battle.getAsJsonObject("receipts").getAsJsonObject("P1").get("storeName").getAsString()).isEqualTo("画像の店");
    }

    @Test
    void imageCardRejectsNonImagesAndFullHands() throws Exception {
        seed(12);
        JsonObject p1 = create();
        String code = p1.get("code").getAsString(), t1 = p1.get("token").getAsString();
        expireWait(code);
        mockMvc.perform(multipart("/api/monster/rooms/" + code + "/image-card")
                        .file(new MockMultipartFile("file", "x.png", "image/png", "not an image".getBytes()))
                        .header("X-Seat-Token", t1).with(fromIp()))
                .andExpect(status().isUnsupportedMediaType());
        for (int i = 0; i < 3; i++) postJson("/api/monster/rooms/" + code + "/flip", t1, "{\"index\":" + i + "}");
        mockMvc.perform(multipart("/api/monster/rooms/" + code + "/image-card")
                        .file(new MockMultipartFile("file", "b.png", "image/png", png("b"))).header("X-Seat-Token", t1).with(fromIp()))
                .andExpect(status().isConflict());
        assertThat(ai.readKeys).isEmpty();
    }

    // ---------- 回数制限・キャッシュ（13章） ----------

    @Test
    void roomCreationIsRateLimitedPerIp() throws Exception {
        seed(2);
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/monster/rooms").with(fromIp())).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        }
        mockMvc.perform(post("/api/monster/rooms").with(fromIp())).andExpect(status().isTooManyRequests());
    }

    @Test
    void invalidRoomCodeIsRejected() throws Exception {
        mockMvc.perform(post("/api/monster/rooms/abc/join").with(fromIp())).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/monster/rooms/ZZZZZZ/join").with(fromIp())).andExpect(status().isNotFound());
    }

    // ---------- ヘルパー ----------

    private JsonObject create() throws Exception {
        return json(mockMvc.perform(post("/api/monster/rooms").with(fromIp())).andExpect(status().isOk()).andReturn());
    }

    private void expireWait(String code) {
        jdbc.update("UPDATE monster_room SET wait_deadline = ? WHERE code = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)), code);
    }

    private long cardIdOfOtherSeat(String code) {
        String json = jdbc.queryForObject("SELECT p.field_json FROM monster_play p JOIN monster_room r ON r.id = p.room_id "
                + "WHERE r.code = ? AND p.seat = 'P2'", String.class, code);
        return JsonParser.parseString(json).getAsJsonArray().get(0).getAsJsonObject().get("cardId").getAsLong();
    }

    private JsonObject waitForBattle(String code, String token) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (getJson("/api/monster/rooms/" + code, token).get("battleReady").getAsBoolean()) {
                return getJson("/api/monster/rooms/" + code + "/battle", token);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("battle did not start");
    }

    private static Set<String> storeNames(JsonObject field) {
        Set<String> names = new HashSet<>();
        for (var r : field.getAsJsonArray("receipts")) {
            names.add(r.getAsJsonObject().getAsJsonObject("receipt").get("branchName").getAsString());
        }
        return names;
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String url, String token, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(url).header("X-Seat-Token", token);
        if (!body.isEmpty()) request = request.contentType("application/json").content(body);
        return mockMvc.perform(request);
    }

    private JsonObject getJson(String url, String token) throws Exception {
        return json(mockMvc.perform(get(url).header("X-Seat-Token", token)).andExpect(status().isOk()).andReturn());
    }

    private static JsonObject json(MvcResult result) throws Exception {
        return JsonParser.parseString(result.getResponse().getContentAsString()).getAsJsonObject();
    }

    private RequestPostProcessor fromIp() {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private void seed(int count) {
        for (int i = 0; i < count; i++) {
            saveReceipt(i, "店" + i, List.of("店" + i, "TEL 03-0000-" + (1000 + i), "合計 ¥" + (1000 + i * 37)));
        }
    }

    private String saveReceipt(int i, String storeName, List<String> lines) {
        String sha = reserve();
        ReceiptStructuredData data = new ReceiptStructuredData(storeName, "支店" + i, i % 2 == 0 ? "スーパー" : "飲食店",
                LocalDateTime.parse("2026-10-0" + (1 + i % 9) + "T1" + (i % 10) + ":2" + (i % 10) + ":00"), 1000L + i * 37,
                "現金", String.format("%06d", i), List.of(
                new ReceiptItemData("米 10kg", "食料品", BigDecimal.ONE, 800L + i, 800L + i),
                new ReceiptItemData("味噌 会員番号 4000100123", "食料品", BigDecimal.ONE, 200L, 200L)));
        return receiptService.store(lines, sha, data).tableName();
    }

    private String reserve() {
        String sha = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
        receiptRepository.reserveImageHash(sha);
        return sha;
    }

    private static byte[] png(String salt) {
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] tail = salt.getBytes();
        byte[] all = new byte[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    @TestConfiguration
    static class StubAiConfiguration {
        @Bean
        @Primary
        StubMonsterAi stubMonsterAi() {
            return new StubMonsterAi();
        }

        @Bean
        @Primary
        com.example.receipt.config.GeminiApiKeys testKeys() {
            return new com.example.receipt.config.GeminiApiKeys("default-key", "player1-key", "player2-key", "monster-key", "analyze-key");
        }
    }

    static class StubMonsterAi implements MonsterAi {
        final List<String> readKeys = new ArrayList<>();
        final List<Integer> cpuHandSizes = new ArrayList<>();
        final List<String> illustrateKeys = new ArrayList<>();
        final List<String> parameterKeys = new ArrayList<>();
        volatile String illustrationSvg;
        volatile MonsterParameters parameters;
        volatile boolean cpuFails;
        volatile int lastCpuHandMaxPower;

        void reset() {
            readKeys.clear();
            cpuHandSizes.clear();
            illustrateKeys.clear();
            parameterKeys.clear();
            illustrationSvg = null;
            parameters = null;
            cpuFails = false;
        }

        @Override
        public ReceiptText readReceipt(byte[] imageBytes, String mimeType, String apiKey) {
            readKeys.add(apiKey);
            return new ReceiptText(List.of("画像の店", "山田太郎様"), null, new ReceiptStructuredData("画像の店", "山田太郎様", "コンビニ",
                    LocalDateTime.parse("2026-10-07T07:07:00"), 777L, "現金", "1", List.of(
                    new ReceiptItemData("おにぎり", "食料品", BigDecimal.ONE, 150L, 150L))));
        }

        @Override
        public Optional<MonsterParameters> generateParameters(ReceiptStructuredData cleanData, int charCount, String apiKey) {
            parameterKeys.add(apiKey);
            return Optional.ofNullable(parameters);
        }

        @Override
        public Optional<String> illustrate(MonsterCard card, String apiKey) {
            illustrateKeys.add(apiKey);
            return Optional.ofNullable(illustrationSvg);
        }

        @Override
        public Optional<CpuChoice> chooseCard(List<MonsterCard> hand, String apiKey) {
            cpuHandSizes.add(hand.size());
            lastCpuHandMaxPower = hand.stream().mapToInt(MonsterCard::power).max().orElse(0);
            if (cpuFails) return Optional.empty();
            return Optional.of(new CpuChoice(hand.getFirst().id(), "テストで選びました"));
        }
    }
}
