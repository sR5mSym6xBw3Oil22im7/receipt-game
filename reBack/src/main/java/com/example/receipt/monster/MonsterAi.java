package com.example.receipt.monster;

import com.example.receipt.dto.ReceiptStructuredData;
import com.example.receipt.dto.ReceiptText;
import com.example.receipt.monster.engine.MonsterCard;
import com.example.receipt.monster.engine.MonsterParameters;

import java.util.List;
import java.util.Optional;

/** モンスターレシート対戦で使う生成AIの呼び出し。テストでは差し替える。 */
public interface MonsterAi {

    /** レシート画像を読み取る（画像カード用）。失敗時は ReceiptException。 */
    ReceiptText readReceipt(byte[] imageBytes, String mimeType, String apiKey);

    /**
     * 個人情報を取り除いたレシートの内容から、カードのパラメータを決める。失敗・形式不正のときは空（呼び出し側で計算式の値を使う）。
     * 戻り値は未検証なので {@link com.example.receipt.monster.engine.MonsterCardGenerator#applyParameters} を通すこと。
     */
    Optional<MonsterParameters> generateParameters(ReceiptStructuredData cleanData, int charCount, String apiKey);

    /** カードのイラストをSVGで作る。検証を通らない・失敗したときは空。 */
    Optional<String> illustrate(MonsterCard card, String apiKey);

    /** コンピュータの手札から1枚を選ぶ。失敗・形式不正のときは空（呼び出し側でPOWER最大を選ぶ）。 */
    Optional<CpuChoice> chooseCard(List<MonsterCard> hand, String apiKey);

    record CpuChoice(long cardId, String reason) {
    }
}
