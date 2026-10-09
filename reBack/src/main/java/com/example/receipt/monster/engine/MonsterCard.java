package com.example.receipt.monster.engine;

/**
 * モンスターカード。レシートの内容（店名・金額・商品）は持たない。
 * imageSha256 はサーバー内部でだけ使い、APIの応答には含めない。
 */
public record MonsterCard(
        Long id,
        String imageSha256,
        String source,
        String name,
        String element,
        String rarity,
        int hp,
        int atk,
        int def,
        int spd,
        int luck,
        int power,
        String skillName,
        double skillPower,
        boolean lucky,
        String flavor,
        String storeCategory,
        String svg,
        int algorithmVersion
) {
    public MonsterCard withId(Long newId) {
        return new MonsterCard(newId, imageSha256, source, name, element, rarity, hp, atk, def, spd, luck, power,
                skillName, skillPower, lucky, flavor, storeCategory, svg, algorithmVersion);
    }

    public MonsterCard withSvg(String newSvg) {
        return new MonsterCard(id, imageSha256, source, name, element, rarity, hp, atk, def, spd, luck, power,
                skillName, skillPower, lucky, flavor, storeCategory, newSvg, algorithmVersion);
    }

    public MonsterCard withSource(String newSource) {
        return new MonsterCard(id, imageSha256, newSource, name, element, rarity, hp, atk, def, spd, luck, power,
                skillName, skillPower, lucky, flavor, storeCategory, svg, algorithmVersion);
    }
}
