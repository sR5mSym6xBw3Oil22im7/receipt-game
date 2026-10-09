package com.example.receipt.monster.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 対戦・勝敗判定（要件定義書 6章）。同じ battleSeed と同じ2枚のカードなら必ず同じ結果になる。
 * 0 が P1、1 が P2。
 */
public final class MonsterBattleEngine {
    public static final int MAX_ROUNDS = 20;

    public record Action(int round, int actor, int target, boolean skill, String skillName, int damage,
                         boolean crit, double elem, int hpAfter) {
    }

    /** winner は "P1"／"P2"／"DRAW"、reason は "KO"／"JUDGE"。 */
    public record Result(long battleSeed, int first, List<Action> log, String winner, String reason, int[] finalHp) {
    }

    private MonsterBattleEngine() {
    }

    /** 炎→風→土→雷→炎 の順に有利（1.25倍）、逆は不利（0.8倍）。 */
    public static double elementMultiplier(String attacker, String defender) {
        if (advantage(attacker).equals(defender)) return 1.25;
        if (advantage(defender).equals(attacker)) return 0.8;
        return 1;
    }

    private static String advantage(String element) {
        return switch (element == null ? "" : element) {
            case "炎" -> "風";
            case "風" -> "土";
            case "土" -> "雷";
            case "雷" -> "炎";
            default -> "-";
        };
    }

    public static Result battle(MonsterCard cardA, MonsterCard cardB, long battleSeed) {
        MonsterRandom rng = new MonsterRandom(battleSeed);
        MonsterCard[] sides = {cardA, cardB};
        int[] hp = {cardA.hp(), cardB.hp()};
        int[] actions = {0, 0};
        int first = cardA.spd() > cardB.spd() ? 0 : cardB.spd() > cardA.spd() ? 1 : ((battleSeed & 1) == 0 ? 0 : 1);
        List<Action> log = new ArrayList<>();
        Integer winner = null;
        String reason = null;

        outer:
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            for (int k : new int[]{first, 1 - first}) {
                int o = 1 - k;
                MonsterCard att = sides[k], def = sides[o];
                actions[k]++;
                boolean skill = actions[k] % 3 == 0;
                double mult = skill ? att.skillPower() : 1;
                double baseDamage = Math.max(1, att.atk() * mult - def.def() * 0.5);
                double variance = 0.9 + 0.2 * rng.next();
                double elem = elementMultiplier(att.element(), def.element());
                boolean crit = rng.next() < att.luck() / 100.0;
                int damage = (int) Math.max(1, Math.round(baseDamage * variance * elem * (crit ? 1.5 : 1)));
                hp[o] = Math.max(0, hp[o] - damage);
                log.add(new Action(round, k, o, skill, skill ? att.skillName() : "通常攻撃", damage, crit, elem, hp[o]));
                if (hp[o] == 0) {
                    winner = k;
                    reason = "KO";
                    break outer;
                }
            }
        }
        String winnerLabel;
        if (winner != null) {
            winnerLabel = winner == 0 ? "P1" : "P2";
        } else {
            reason = "JUDGE";
            double ratioA = (double) hp[0] / cardA.hp();
            double ratioB = (double) hp[1] / cardB.hp();
            if (ratioA != ratioB) winnerLabel = ratioA > ratioB ? "P1" : "P2";
            else if (cardA.power() != cardB.power()) winnerLabel = cardA.power() > cardB.power() ? "P1" : "P2";
            else winnerLabel = "DRAW";
        }
        return new Result(battleSeed, first, log, winnerLabel, reason, hp);
    }
}
