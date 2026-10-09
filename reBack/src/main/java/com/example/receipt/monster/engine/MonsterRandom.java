package com.example.receipt.monster.engine;

/**
 * デモ（static/demo/engine.js）の mulberry32 と同じ値を返す乱数。
 * 同じ種なら同じ列になるので、カードの絵と対戦結果を再現できる。
 */
public final class MonsterRandom {
    private int state;

    public MonsterRandom(long seed) {
        this.state = (int) seed;
    }

    /** 0以上1未満の値を返す。 */
    public double next() {
        state += 0x6D2B79F5;
        int t = state;
        t = (t ^ (t >>> 15)) * (t | 1);
        t ^= t + (t ^ (t >>> 7)) * (t | 61);
        return Integer.toUnsignedLong(t ^ (t >>> 14)) / 4294967296.0;
    }
}
