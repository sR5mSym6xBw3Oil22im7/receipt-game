package com.example.receipt.monster.engine;

/**
 * 生成AIが決めたカードのパラメータ（未検証）。
 * {@link MonsterCardGenerator#applyParameters} で範囲に収めてからカードに反映する。
 */
public record MonsterParameters(
        String name,
        String element,
        int hp,
        int atk,
        int def,
        int spd,
        int luck,
        String skillName,
        double skillPower,
        String flavor
) {
}
