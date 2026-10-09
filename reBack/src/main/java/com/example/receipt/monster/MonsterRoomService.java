package com.example.receipt.monster;

import com.example.receipt.config.GeminiApiKeys;
import com.example.receipt.dto.ReceiptText;
import com.example.receipt.exception.ReceiptException;
import com.example.receipt.monster.MonsterCardRepository.StoredCard;
import com.example.receipt.monster.MonsterRoomRepository.FieldEntry;
import com.example.receipt.monster.MonsterRoomRepository.Play;
import com.example.receipt.monster.MonsterRoomRepository.Room;
import com.example.receipt.monster.MonsterViews.ActionView;
import com.example.receipt.monster.MonsterViews.BattleView;
import com.example.receipt.monster.MonsterViews.CardView;
import com.example.receipt.monster.MonsterViews.FieldReceipt;
import com.example.receipt.monster.MonsterViews.FieldView;
import com.example.receipt.monster.MonsterViews.FlipResponse;
import com.example.receipt.monster.MonsterViews.ImageCardResponse;
import com.example.receipt.monster.MonsterViews.ReceiptView;
import com.example.receipt.monster.MonsterViews.RoomView;
import com.example.receipt.monster.MonsterViews.SeatResponse;
import com.example.receipt.monster.engine.MonsterBattleEngine;
import com.example.receipt.monster.engine.MonsterCard;
import com.example.receipt.monster.engine.PersonalInfoSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * ルーム・カード選択・対戦の進行（要件定義書 3章・7章・8章・12章）。
 * 勝敗はサーバーで計算し、対戦開始まで相手のカード・レシートは応答に含めない。
 */
@Service
public class MonsterRoomService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MonsterRoomService.class);
    static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final Pattern CODE_PATTERN = Pattern.compile("[A-HJ-NP-Z2-9]{6}");
    public static final int HAND_LIMIT = 3;
    public static final int FIELD_SIZE = 9;
    public static final int SHUFFLE_MIN = 10;
    static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    static final String CPU_FALLBACK_REASON = "自動で選びました";
    private static final Duration CLOSED_ROOM_TTL = Duration.ofMinutes(10);

    static final String WAITING = "WAITING", SELECTING = "SELECTING", BATTLE = "BATTLE", CLOSED = "CLOSED";

    private final MonsterRoomRepository rooms;
    private final MonsterCardRepository cards;
    private final MonsterCardService cardService;
    private final MonsterAi ai;
    private final GeminiApiKeys keys;
    private final MonsterSettings settings;
    private final MonsterRateLimiter limiter;
    private final MonsterTaskRunner tasks;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public MonsterRoomService(MonsterRoomRepository rooms, MonsterCardRepository cards, MonsterCardService cardService,
                              MonsterAi ai, GeminiApiKeys keys, MonsterSettings settings, MonsterRateLimiter limiter,
                              MonsterTaskRunner tasks, TransactionTemplate tx) {
        this.rooms = rooms;
        this.cards = cards;
        this.cardService = cardService;
        this.ai = ai;
        this.keys = keys;
        this.settings = settings;
        this.limiter = limiter;
        this.tasks = tasks;
        this.tx = tx;
        this.clock = Clock.systemUTC();
        rooms.ensureSchema();
    }

    // ---------- ロビー・待機 ----------

    public SeatResponse create() {
        return inTx(() -> {
            Instant now = clock.instant();
            String code;
            do {
                code = newCode();
            } while (rooms.codeExists(code));
            String token = newToken();
            rooms.insertRoom(code, hash(token), now, now.plus(settings.waitTimeout()), now.plus(settings.roomTtl()));
            Room room = lock(code);
            return new SeatResponse(code, "P1", token, view(room, "P1"));
        });
    }

    public SeatResponse join(String rawCode) {
        String code = normalizeCode(rawCode);
        return inTx(() -> {
            Room room = advance(lock(code));
            if (!WAITING.equals(room.status()) || room.p2TokenHash() != null) {
                throw new ReceiptException(HttpStatus.CONFLICT, "ROOM_NOT_JOINABLE",
                        "このルームには参加できません（対戦が始まっているか、満員です）。");
            }
            String token = newToken();
            Instant now = clock.instant();
            room = new Room(room.id(), room.code(), room.status(), room.mode(), room.round(), room.p1TokenHash(), hash(token),
                    room.p1SeenAt(), now, room.waitDeadline(), now.plus(settings.roomTtl()), null, null, null);
            room = startRound(room);
            return new SeatResponse(code, "P2", token, view(room, "P2"));
        });
    }

    public RoomView state(String code, String token) {
        return inTx(() -> {
            Seat s = enter(code, token);
            return view(s.room, s.seat);
        });
    }

    public void leave(String code, String token) {
        inTx(() -> {
            Seat s = enter(code, token);
            if (!CLOSED.equals(s.room.status())) close(s.room, s.seat, null);
            return null;
        });
    }

    // ---------- カード選択 ----------

    public FieldView field(String code, String token) {
        return inTx(() -> {
            Seat s = enter(code, token);
            return fieldView(s.room, rooms.play(s.room.id(), s.seat));
        });
    }

    public FieldView shuffle(String code, String token) {
        return inTx(() -> {
            Seat s = enterSelecting(code, token);
            Play me = rooms.play(s.room.id(), s.seat);
            Play other = rooms.play(s.room.id(), other(s.seat));
            List<Long> active = cards.activeCardIds();
            if (active.size() < SHUFFLE_MIN || !me.hand().isEmpty() || me.confirmed()) {
                throw conflict("SHUFFLE_NOT_ALLOWED", "今はシャッフルできません（レシートを選んだ後・決定後、またはレシートが10件未満）。");
            }
            Set<Long> excluded = new HashSet<>(other.fieldCardIds());
            excluded.addAll(other.hand());
            if (other.imageCardId() != null) excluded.add(other.imageCardId());
            if (me.imageCardId() != null) excluded.add(me.imageCardId());
            List<Long> pool = new ArrayList<>(active);
            pool.removeAll(excluded);
            Collections.shuffle(pool, random);
            me = me.withField(entries(pool.subList(0, Math.min(FIELD_SIZE, pool.size()))), new ArrayList<>());
            rooms.savePlay(me);
            return fieldView(s.room, me);
        });
    }

    public FlipResponse flip(String code, String token, int index) {
        return inTx(() -> {
            Seat s = enterSelecting(code, token);
            Play me = rooms.play(s.room.id(), s.seat);
            if (me.confirmed()) throw conflict("ALREADY_CONFIRMED", "カードは決定済みです。");
            if (index < 0 || index >= me.field().size()) {
                throw new ReceiptException(HttpStatus.BAD_REQUEST, "INVALID_INDEX", "レシートの番号が正しくありません。");
            }
            if (me.flipped().contains(index)) throw conflict("ALREADY_FLIPPED", "このレシートは選択済みです。");
            if (me.hand().size() >= HAND_LIMIT) throw conflict("HAND_FULL", "手札は" + HAND_LIMIT + "枚までです。");
            long cardId = me.field().get(index).cardId();
            MonsterCard card = cards.findById(cardId)
                    .filter(c -> MonsterCardRepository.ACTIVE.equals(c.status()))
                    .map(StoredCard::card)
                    .orElseThrow(() -> conflict("CARD_REMOVED", "このレシートは削除されたため選べません。"));
            List<Long> hand = new ArrayList<>(me.hand());
            hand.add(cardId);
            List<Integer> flipped = new ArrayList<>(me.flipped());
            flipped.add(index);
            me = me.withHand(hand, flipped);
            rooms.savePlay(me);
            return new FlipResponse(CardView.of(card), fieldView(s.room, me));
        });
    }

    public ImageCardResponse imageCard(String code, String token, byte[] image) {
        String mime = detectImageType(image);
        // 1. 状態の確認（Geminiの呼び出し中は行ロックを持たない）
        Seat s = inTx(() -> {
            Seat seat = enterSelecting(code, token);
            checkCanMakeImageCard(rooms.play(seat.room.id(), seat.seat));
            return seat;
        });
        String seat = s.seat;
        int round = s.room.round();
        String keyName = "P1".equals(seat) ? "PLAYER1" : "PLAYER2";
        String apiKey = "P1".equals(seat) ? keys.player1() : keys.player2();
        String sha = sha256Hex(image);

        // 2. カードを用意する（既存の有効なカードがあれば、それを使う）
        Optional<StoredCard> existing = cards.findBySha(sha);
        MonsterCard card;
        ReceiptView receipt;
        boolean reused;
        if (existing.isPresent() && MonsterCardRepository.ACTIVE.equals(existing.get().status())) {
            card = existing.get().card();
            receipt = cards.loadReceipt(existing.get().receiptTableName()).map(ReceiptView::of)
                    .orElseThrow(() -> conflict("CARD_REMOVED", "このレシートは削除されたため使えません。"));
            reused = true;
        } else {
            if (!limiter.tryAcquireGemini(keyName)) {
                throw new ReceiptException(HttpStatus.TOO_MANY_REQUESTS, "GEMINI_DAILY_LIMIT",
                        "本日の画像からのカード作成の上限に達しました。レシートを選んで対戦できます。");
            }
            ReceiptText read = readReceipt(image, mime, apiKey);
            PersonalInfoSanitizer.Result clean;
            try {
                clean = PersonalInfoSanitizer.sanitize(read.structuredData(), read.lines());
            } catch (RuntimeException e) {
                throw new ReceiptException(HttpStatus.UNPROCESSABLE_CONTENT, "PERSONAL_INFO_CHECK_FAILED",
                        "個人情報の確認ができなかったため、この画像からはカードを作れません。");
            }
            receipt = ReceiptView.of(clean.data() == null
                    ? new com.example.receipt.dto.ReceiptStructuredData(null, null, "その他", null, null, null, null, List.of())
                    : clean.data());
            if (existing.isPresent()) {
                card = existing.get().card();
                reused = true;
            } else {
                MonsterCard generated = cardService.generateWithAi(clean.data(), sha, clean.lines(), keyName,
                        List.of(new MonsterCardService.AiKey(keyName, apiKey))).card();
                card = cards.insertDraft(generated).card();
                reused = false;
            }
        }

        // 3. 画像カードとして登録する（1体のみ。作り直すと置き換わる）
        MonsterCard finalCard = card;
        ReceiptView finalReceipt = receipt;
        boolean finalReused = reused;
        return inTx(() -> {
            Seat again = enterSelecting(code, token);
            if (again.room.round() != round) throw conflict("ROUND_CHANGED", "再戦が始まったため、画像カードは使えません。");
            Play me = rooms.play(again.room.id(), seat);
            checkCanMakeImageCard(me);
            Play other = rooms.play(again.room.id(), other(seat));
            Set<Long> taken = new HashSet<>(other.fieldCardIds());
            taken.addAll(other.hand());
            if (other.imageCardId() != null) taken.add(other.imageCardId());
            taken.addAll(me.hand());
            if (taken.contains(finalCard.id())) {
                throw conflict("CARD_IN_USE", "このレシートのカードは、すでに使われています。別の画像を選んでください。");
            }
            me = me.withImage(finalCard.id(), finalReceipt);
            rooms.savePlay(me);
            String message = finalReused
                    ? "この画像のカードは作成済みです。新しく作らず、既存のカードを使います。"
                    : "カードができました！このカードで対戦できます。";
            return new ImageCardResponse(CardView.of(finalCard), finalReused, message, fieldView(again.room, me));
        });
    }

    public RoomView select(String code, String token, long cardId) {
        Room after = inTx(() -> {
            Seat s = enterSelecting(code, token);
            Play me = rooms.play(s.room.id(), s.seat);
            if (me.confirmed()) throw conflict("ALREADY_CONFIRMED", "カードは決定済みです。");
            boolean allowed = me.hand().contains(cardId) || Long.valueOf(cardId).equals(me.imageCardId());
            if (!allowed) {
                throw new ReceiptException(HttpStatus.BAD_REQUEST, "CARD_NOT_IN_HAND", "手札または画像カードから選んでください。");
            }
            MonsterCard card = cards.findById(cardId).map(StoredCard::card)
                    .orElseThrow(() -> conflict("CARD_REMOVED", "このカードは削除されたため選べません。"));
            me = me.withSelected(card);
            rooms.savePlay(me);
            Play other = rooms.play(s.room.id(), other(s.seat));
            Room room = s.room;
            if (other.confirmed()) room = startBattle(room);
            return room;
        });
        if ("CPU".equals(after.mode()) && SELECTING.equals(after.status())) {
            int round = after.round();
            tasks.run("cpu", () -> cpuTurn(code, round));
        }
        return state(code, token);
    }

    // ---------- 対戦・結果 ----------

    public BattleView battle(String code, String token) {
        return inTx(() -> {
            Seat s = enter(code, token);
            if (!BATTLE.equals(s.room.status())) throw conflict("BATTLE_NOT_READY", "対戦はまだ始まっていません。");
            return rooms.battle(s.room.id(), s.room.round())
                    .orElseThrow(() -> conflict("BATTLE_NOT_READY", "対戦はまだ始まっていません。"));
        });
    }

    public RoomView rematch(String code, String token, int round) {
        return inTx(() -> {
            Seat s = enter(code, token);
            Room room = s.room;
            if (room.round() == round) {
                if (!BATTLE.equals(room.status())) throw conflict("REMATCH_NOT_ALLOWED", "今は再戦できません。");
                room = startRound(room);
            }
            return view(room, s.seat);
        });
    }

    // ---------- コンピュータ（8章） ----------

    void cpuTurn(String code, int round) {
        // 1. 自分に配られたレシートから、1〜3枚（乱数）を手札にする
        List<Long> handIds = inTx(() -> {
            Room room = lock(code);
            if (!SELECTING.equals(room.status()) || room.round() != round) return List.<Long>of();
            Play cpu = rooms.play(room.id(), "P2");
            if (cpu.confirmed()) return List.<Long>of();
            Play human = rooms.play(room.id(), "P1");
            int want = 1 + random.nextInt(HAND_LIMIT);
            List<FieldEntry> field = new ArrayList<>(cpu.field());
            if (field.size() < want) {
                Set<Long> used = new HashSet<>(human.fieldCardIds());
                used.addAll(human.hand());
                if (human.imageCardId() != null) used.add(human.imageCardId());
                used.addAll(cpu.fieldCardIds());
                List<Long> undealt = new ArrayList<>(cards.activeCardIds());
                undealt.removeAll(used);
                Collections.shuffle(undealt, random);
                field.addAll(entries(undealt.subList(0, Math.min(want - field.size(), undealt.size()))));
            }
            List<Long> hand = field.stream().limit(want).map(FieldEntry::cardId).toList();
            rooms.savePlay(cpu.withField(field, cpu.flipped()).withHand(new ArrayList<>(hand), new ArrayList<>()));
            return hand;
        });
        List<MonsterCard> hand = handIds.stream().map(cards::findById).flatMap(Optional::stream).map(StoredCard::card).toList();
        if (hand.isEmpty()) {
            if (!handIds.isEmpty()) LOGGER.warn("CPU hand cards were removed before choosing.");
            return;
        }

        // 2. 手札から1枚を選ぶ（Geminiが使えないときは POWER 最大）
        Optional<MonsterAi.CpuChoice> choice = limiter.tryAcquireGemini("PLAYER2")
                ? ai.chooseCard(hand, keys.player2()) : Optional.empty();
        MonsterCard pick = choice.flatMap(c -> hand.stream().filter(h -> h.id() == c.cardId()).findFirst())
                .orElseGet(() -> hand.stream().max(Comparator.comparingInt(MonsterCard::power)).orElseThrow());
        String reason = choice.filter(c -> c.cardId() == pick.id()).map(MonsterAi.CpuChoice::reason).orElse(CPU_FALLBACK_REASON);

        // 3. 決定する。P1が決定済みなら対戦を始める
        inTx(() -> {
            Room room = lock(code);
            if (!SELECTING.equals(room.status()) || room.round() != round) return null;
            Play cpu = rooms.play(room.id(), "P2");
            if (cpu.confirmed()) return null;
            rooms.savePlay(cpu.withSelected(pick));
            room = new Room(room.id(), room.code(), room.status(), room.mode(), room.round(), room.p1TokenHash(),
                    room.p2TokenHash(), room.p1SeenAt(), room.p2SeenAt(), room.waitDeadline(), room.expiresAt(), reason,
                    room.notice(), room.closedBy());
            rooms.updateRoom(room);
            if (rooms.play(room.id(), "P1").confirmed()) startBattle(room);
            return null;
        });
    }

    // ---------- 内部処理 ----------

    private record Seat(Room room, String seat) {
    }

    private Seat enter(String code, String token) {
        Room room = lock(normalizeCode(code));
        String seat = authorize(room, token);
        Instant now = clock.instant();
        Instant expires = CLOSED.equals(room.status()) ? room.expiresAt() : now.plus(settings.roomTtl());
        room = new Room(room.id(), room.code(), room.status(), room.mode(), room.round(), room.p1TokenHash(),
                room.p2TokenHash(), "P1".equals(seat) ? now : room.p1SeenAt(), "P2".equals(seat) ? now : room.p2SeenAt(),
                room.waitDeadline(), expires, room.cpuReason(), room.notice(), room.closedBy());
        rooms.updateRoom(room);
        return new Seat(advance(room), seat);
    }

    private Seat enterSelecting(String code, String token) {
        Seat s = enter(code, token);
        if (!SELECTING.equals(s.room.status())) throw conflict("NOT_SELECTING", "今はカードを選べません。");
        return s;
    }

    /** 待機時間を過ぎてもP2が参加しなければ、コンピュータ対戦にする。 */
    private Room advance(Room room) {
        if (WAITING.equals(room.status()) && room.p2TokenHash() == null && !clock.instant().isBefore(room.waitDeadline())) {
            room = new Room(room.id(), room.code(), room.status(), "CPU", room.round(), room.p1TokenHash(), null,
                    room.p1SeenAt(), null, room.waitDeadline(), room.expiresAt(), null, null, null);
            return startRound(room);
        }
        return room;
    }

    /** 配り直す（7.4）。手札・画像カード・選択はリセットする。 */
    private Room startRound(Room room) {
        rooms.deleteBattles(room.id());
        List<Long> active = new ArrayList<>(cards.activeCardIds());
        if (active.size() < 2) {
            return close(room, null, "対戦できるカードが足りません（有効なカードが2枚以上必要です）。ロビーへ戻ります。");
        }
        Collections.shuffle(active, random);
        List<Long> p1, p2;
        if ("CPU".equals(room.mode())) {
            // コンピュータに1枚、あなたに最大9枚（コンピュータは決定時に追加で引く）
            p2 = active.subList(0, 1);
            p1 = active.subList(1, Math.min(1 + FIELD_SIZE, active.size()));
        } else {
            int first = Math.min(FIELD_SIZE, (active.size() + 1) / 2);
            p1 = active.subList(0, first);
            p2 = active.subList(first, Math.min(first + FIELD_SIZE, active.size()));
        }
        rooms.resetPlay(room.id(), "P1", entries(p1));
        rooms.resetPlay(room.id(), "P2", entries(p2));
        Room next = new Room(room.id(), room.code(), SELECTING, room.mode(), room.round() + 1, room.p1TokenHash(),
                room.p2TokenHash(), room.p1SeenAt(), room.p2SeenAt(), room.waitDeadline(), room.expiresAt(), null, null, null);
        rooms.updateRoom(next);
        return next;
    }

    private Room close(Room room, String closedBy, String notice) {
        rooms.deleteBattles(room.id());
        rooms.resetPlay(room.id(), "P1", null);
        rooms.resetPlay(room.id(), "P2", null);
        Room closed = new Room(room.id(), room.code(), CLOSED, room.mode(), room.round(), room.p1TokenHash(),
                room.p2TokenHash(), room.p1SeenAt(), room.p2SeenAt(), room.waitDeadline(),
                clock.instant().plus(CLOSED_ROOM_TTL), room.cpuReason(), notice, closedBy);
        rooms.updateRoom(closed);
        return closed;
    }

    private Room startBattle(Room room) {
        Play p1 = rooms.play(room.id(), "P1");
        Play p2 = rooms.play(room.id(), "P2");
        long seed = Integer.toUnsignedLong(random.nextInt());
        MonsterBattleEngine.Result result = MonsterBattleEngine.battle(p1.selected(), p2.selected(), seed);
        Map<String, Integer> finalHp = new LinkedHashMap<>();
        finalHp.put("P1", result.finalHp()[0]);
        finalHp.put("P2", result.finalHp()[1]);
        Map<String, CardView> shown = new LinkedHashMap<>();
        shown.put("P1", CardView.of(p1.selected()));
        shown.put("P2", CardView.of(p2.selected()));
        Map<String, ReceiptView> receipts = new LinkedHashMap<>();
        receipts.put("P1", receiptOf(p1));
        receipts.put("P2", receiptOf(p2));
        BattleView battle = new BattleView(room.round(), seed, MonsterViews.seat(result.first()),
                result.log().stream().map(ActionView::of).toList(), result.winner(), result.reason(), finalHp, shown,
                receipts, room.mode(), "CPU".equals(room.mode()) ? room.cpuReason() : null);
        rooms.insertBattle(room.id(), room.round(), battle);
        Room next = new Room(room.id(), room.code(), BATTLE, room.mode(), room.round(), room.p1TokenHash(),
                room.p2TokenHash(), room.p1SeenAt(), room.p2SeenAt(), room.waitDeadline(), room.expiresAt(),
                room.cpuReason(), room.notice(), room.closedBy());
        rooms.updateRoom(next);
        return next;
    }

    private static ReceiptView receiptOf(Play play) {
        long id = play.selected().id();
        if (play.imageCardId() != null && play.imageCardId() == id && play.imageReceipt() != null) return play.imageReceipt();
        return play.field().stream().filter(e -> e.cardId() == id).map(FieldEntry::receipt).findFirst().orElse(null);
    }

    /** 配布するレシートの表示内容を、既存のテーブルから読んでプレイ用にコピーする。 */
    private List<FieldEntry> entries(List<Long> cardIds) {
        List<FieldEntry> result = new ArrayList<>();
        for (Long id : cardIds) {
            cards.findById(id)
                    .flatMap(c -> cards.loadReceipt(c.receiptTableName()))
                    .ifPresent(r -> result.add(new FieldEntry(id, ReceiptView.of(r))));
        }
        return result;
    }

    private FieldView fieldView(Room room, Play me) {
        int activeCount = cards.activeCardIds().size();
        boolean shuffleVisible = activeCount >= SHUFFLE_MIN;
        List<FieldReceipt> receipts = new ArrayList<>();
        for (int i = 0; i < me.field().size(); i++) {
            receipts.add(new FieldReceipt(i, me.flipped().contains(i), me.field().get(i).receipt()));
        }
        List<CardView> hand = me.hand().stream().map(cards::findById).flatMap(Optional::stream)
                .map(c -> CardView.of(c.card())).toList();
        CardView image = me.imageCardId() == null ? null
                : cards.findById(me.imageCardId()).map(c -> CardView.of(c.card())).orElse(null);
        boolean selecting = SELECTING.equals(room.status());
        return new FieldView(room.round(), receipts, shuffleVisible,
                selecting && shuffleVisible && me.hand().isEmpty() && !me.confirmed(),
                hand, image, me.selected() == null ? null : me.selected().id(), me.confirmed(), HAND_LIMIT);
    }

    private RoomView view(Room room, String seat) {
        Instant now = clock.instant();
        boolean cpu = "CPU".equals(room.mode());
        boolean opponentJoined = cpu || room.p2TokenHash() != null;
        Long waitLeft = WAITING.equals(room.status())
                ? Math.max(0, Duration.between(now, room.waitDeadline()).toSeconds()) : null;
        boolean youConfirmed = false;
        String opponentState = "WAITING";
        if (SELECTING.equals(room.status()) || BATTLE.equals(room.status())) {
            youConfirmed = rooms.play(room.id(), seat).confirmed();
            opponentState = rooms.play(room.id(), other(seat)).confirmed() ? "CONFIRMED" : "SELECTING";
        }
        Instant opponentSeen = "P1".equals(seat) ? room.p2SeenAt() : room.p1SeenAt();
        boolean unresponsive = !cpu && opponentJoined && !CLOSED.equals(room.status()) && opponentSeen != null
                && opponentSeen.isBefore(now.minus(settings.unresponsiveAfter()));
        String notice = room.notice();
        if (CLOSED.equals(room.status()) && notice == null) {
            notice = seat.equals(room.closedBy()) ? "ルームを閉じました。" : "相手がルームを閉じました。";
        }
        return new RoomView(room.code(), seat, room.status(), room.mode(), room.round(), waitLeft, opponentJoined,
                opponentState, unresponsive, youConfirmed, BATTLE.equals(room.status()), notice);
    }

    private void checkCanMakeImageCard(Play me) {
        if (me.confirmed()) throw conflict("ALREADY_CONFIRMED", "カードは決定済みです。");
        if (me.hand().size() >= HAND_LIMIT) throw conflict("HAND_FULL", "手札が" + HAND_LIMIT + "枚のため、画像からは作れません。");
    }

    private ReceiptText readReceipt(byte[] image, String mime, String apiKey) {
        try {
            return ai.readReceipt(image, mime, apiKey);
        } catch (ReceiptException e) {
            if (e.status() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new ReceiptException(HttpStatus.TOO_MANY_REQUESTS, "GEMINI_QUOTA_EXCEEDED",
                        "画像の読み取りが混み合っています。しばらく待つか、レシートを選んで対戦してください。");
            }
            if (e.status() == HttpStatus.UNPROCESSABLE_CONTENT) {
                throw new ReceiptException(HttpStatus.UNPROCESSABLE_CONTENT, "NO_TEXT_FOUND", "画像からレシートを読み取れませんでした。");
            }
            LOGGER.warn("Monster image card reading failed: code={}", e.code());
            throw new ReceiptException(HttpStatus.BAD_GATEWAY, "IMAGE_READ_FAILED",
                    "画像を読み取れませんでした。時間をおいて試すか、レシートを選んで対戦してください。");
        }
    }

    /** JPEG/PNGかどうかを、拡張子ではなく中身で判定する。 */
    static String detectImageType(byte[] image) {
        if (image == null || image.length == 0) {
            throw new ReceiptException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "画像ファイルを選択してください。");
        }
        if (image.length > MAX_IMAGE_BYTES) {
            throw new ReceiptException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "画像は5 MiB以下にしてください。");
        }
        if (image.length >= 3 && (image[0] & 0xFF) == 0xFF && (image[1] & 0xFF) == 0xD8 && (image[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (image.length >= png.length && java.util.Arrays.equals(java.util.Arrays.copyOf(image, png.length), png)) {
            return "image/png";
        }
        throw new ReceiptException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "JPEGまたはPNGの画像を選んでください。");
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void deleteExpiredRooms() {
        int removed = rooms.deleteExpired(clock.instant());
        if (removed > 0) LOGGER.info("Deleted {} expired monster rooms.", removed);
    }

    private Room lock(String code) {
        return rooms.lockByCode(code)
                .orElseThrow(() -> new ReceiptException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "ルームが見つかりません。コードを確認してください。"));
    }

    private String authorize(Room room, String token) {
        if (token != null && !token.isBlank()) {
            byte[] given = hash(token).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(given, room.p1TokenHash().getBytes(StandardCharsets.US_ASCII))) return "P1";
            if (room.p2TokenHash() != null
                    && MessageDigest.isEqual(given, room.p2TokenHash().getBytes(StandardCharsets.US_ASCII))) return "P2";
        }
        throw new ReceiptException(HttpStatus.FORBIDDEN, "SEAT_REQUIRED", "このルームの席がありません。ロビーからやり直してください。");
    }

    static String normalizeCode(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new ReceiptException(HttpStatus.BAD_REQUEST, "INVALID_ROOM_CODE", "ルームコードは6桁の英数字で入力してください。");
        }
        return code;
    }

    private String newCode() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        return sb.toString();
    }

    /** 席トークン：256ビットの乱数。DBにはハッシュだけ保存する。 */
    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        return sha256Hex(token.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String other(String seat) {
        return "P1".equals(seat) ? "P2" : "P1";
    }

    private static ReceiptException conflict(String code, String message) {
        return new ReceiptException(HttpStatus.CONFLICT, code, message);
    }

    private <T> T inTx(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }
}
