package com.example.receipt.monster;

import com.example.receipt.dto.ReceiptItemData;
import com.example.receipt.dto.ReceiptStructuredData;
import com.example.receipt.monster.engine.MonsterBattleEngine;
import com.example.receipt.monster.engine.MonsterCard;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** モンスターレシート対戦APIの応答。画像ハッシュ・テーブル名・行テキスト・購入の詳細は含めない。 */
public final class MonsterViews {

    private MonsterViews() {
    }

    public record CardView(long id, String name, String element, String rarity, int hp, int atk, int def, int spd, int luck,
                           int power, String skillName, double skillPower, boolean lucky, String flavor,
                           String storeCategory, String source, String svg) {
        public static CardView of(MonsterCard c) {
            return new CardView(c.id(), c.name(), c.element(), c.rarity(), c.hp(), c.atk(), c.def(), c.spd(), c.luck(),
                    c.power(), c.skillName(), c.skillPower(), c.lucky(), c.flavor(), c.storeCategory(), c.source(), c.svg());
        }
    }

    /**
     * レシートの表示項目（doc/15_api_specification.html 6章 ReceiptView）。
     * 対戦APIはログイン不要なので、ゲームに必要な店舗カテゴリー・金額帯・品数だけを返す。
     * 店名・支店名・購入日時・レシート番号・商品名・単価・支払方法は返さない。
     */
    public record ReceiptView(String storeCategory, String amountRange, int itemCount) {
        public static ReceiptView of(ReceiptStructuredData d) {
            long itemCount = d.safeItems().stream().filter(Objects::nonNull)
                    .filter(it -> it.name() != null && !it.name().isBlank()).count();
            Long total = d.totalAmount() != null ? d.totalAmount()
                    : d.safeItems().stream().filter(Objects::nonNull).map(ReceiptItemData::amount)
                    .filter(Objects::nonNull).mapToLong(Long::longValue).sum();
            return new ReceiptView(d.storeCategory() == null ? "その他" : d.storeCategory(), amountRange(total),
                    (int) itemCount);
        }

        /** 合計金額を金額帯にまとめる（正確な金額は返さない）。 */
        static String amountRange(Long total) {
            if (total == null) return "金額不明";
            if (total < 1_000) return "1,000円未満";
            if (total < 3_000) return "1,000〜2,999円";
            if (total < 5_000) return "3,000〜4,999円";
            if (total < 10_000) return "5,000〜9,999円";
            return "10,000円以上";
        }
    }

    public record FieldReceipt(int index, boolean selected, ReceiptView receipt) {
    }

    public record FieldView(int round, List<FieldReceipt> receipts, boolean shuffleVisible, boolean canShuffle,
                            List<CardView> hand, CardView imageCard, Long selectedCardId, boolean confirmed, int handLimit) {
    }

    public record RoomView(String code, String seat, String status, String mode, int round, Long waitSecondsLeft,
                           boolean opponentJoined, String opponentState, boolean opponentUnresponsive,
                           boolean youConfirmed, boolean battleReady, String notice) {
    }

    public record SeatResponse(String code, String seat, String token, RoomView room) {
    }

    public record FlipResponse(CardView card, FieldView field) {
    }

    public record ImageCardResponse(CardView card, boolean reused, String message, FieldView field) {
    }

    public record ActionView(int round, String actor, String target, boolean skill, String skillName, int damage,
                             boolean crit, double elem, int hpAfter) {
        static ActionView of(MonsterBattleEngine.Action a) {
            return new ActionView(a.round(), seat(a.actor()), seat(a.target()), a.skill(), a.skillName(), a.damage(),
                    a.crit(), a.elem(), a.hpAfter());
        }
    }

    public record BattleView(int round, long battleSeed, String first, List<ActionView> log, String winner, String reason,
                             Map<String, Integer> finalHp, Map<String, CardView> cards, Map<String, ReceiptView> receipts,
                             String mode, String cpuReason) {
    }

    static String seat(int index) {
        return index == 0 ? "P1" : "P2";
    }
}
