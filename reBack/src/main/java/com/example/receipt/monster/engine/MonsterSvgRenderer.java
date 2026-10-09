package com.example.receipt.monster.engine;

import java.util.List;
import java.util.Map;

/**
 * 代替イラスト（要件定義書 5.5）。属性別の体形に、目・角・牙・ほほ紅などを種で変えたSVGを作る。
 * 生成AIのSVGが検証を通らない場合や、AIを使わない設定のときに使う。デモの fallbackSvg と同じ絵になる。
 */
public final class MonsterSvgRenderer {
    static final Map<String, List<String>> PALETTE = Map.of(
            "炎", List.of("#e4572e", "#ffc065", "#7a1f0c"),
            "土", List.of("#a7803f", "#e2c98a", "#4f3a14"),
            "風", List.of("#3fb596", "#c5f3e4", "#17594a"),
            "雷", List.of("#e8b800", "#8f7bff", "#40358f"),
            "無", List.of("#8b8fa3", "#e2e4ee", "#454859"));

    private static final Map<String, String> BODY = Map.of(
            "炎", "<path d=\"M100 26C132 68 170 98 162 140C156 172 128 186 100 186C72 186 44 172 38 140C30 98 68 68 100 26Z\"/>",
            "土", "<path d=\"M44 80C44 56 66 44 100 44C134 44 156 56 156 80L166 140C168 170 140 184 100 184C60 184 32 170 34 140Z\"/>",
            "風", "<g><circle cx=\"70\" cy=\"120\" r=\"42\"/><circle cx=\"118\" cy=\"104\" r=\"50\"/><circle cx=\"138\" cy=\"138\" r=\"38\"/><rect x=\"60\" y=\"120\" width=\"90\" height=\"56\" rx=\"26\"/></g>",
            "雷", "<polygon points=\"100,22 162,68 176,140 132,184 68,184 24,140 38,68\"/>",
            "無", "<circle cx=\"100\" cy=\"112\" r=\"74\"/>");

    private MonsterSvgRenderer() {
    }

    public static List<String> palette(String element) {
        return PALETTE.getOrDefault(element, PALETTE.get("無"));
    }

    public static String fallbackSvg(String element, String rarity, long seed) {
        MonsterRandom rng = new MonsterRandom(seed ^ 0xA5A5A5A5L);
        List<String> colors = palette(element);
        String main = colors.get(0), light = colors.get(1), dark = colors.get(2);
        int hueShift = (int) Math.floor(rng.next() * 24) - 12;
        String gid = "g" + Long.toHexString(seed);
        int eyes = 1 + (int) Math.floor(rng.next() * 3);
        int horns = (int) Math.floor(rng.next() * 4);
        boolean fangs = rng.next() < 0.5;
        boolean cheeks = rng.next() < 0.6;
        String body = BODY.getOrDefault(element, BODY.get("無"));

        int eyeY = 108;
        int[] eyeXs = eyes == 1 ? new int[]{100} : eyes == 2 ? new int[]{76, 124} : new int[]{62, 100, 138};
        StringBuilder eyeMarkup = new StringBuilder();
        for (int x : eyeXs) {
            eyeMarkup.append("<circle cx=\"").append(x).append("\" cy=\"").append(eyeY).append("\" r=\"15\" fill=\"#fff\"/>")
                    .append("<circle cx=\"").append(x + 2).append("\" cy=\"").append(eyeY + 2).append("\" r=\"8\" fill=\"#241a10\"/>")
                    .append("<circle cx=\"").append(x + 5).append("\" cy=\"").append(eyeY - 2).append("\" r=\"3\" fill=\"#fff\"/>");
        }
        StringBuilder hornMarkup = new StringBuilder();
        for (int i = 0; i < horns; i++) {
            double x = horns == 1 ? 100 : 56 + (88.0 / (horns - 1)) * i;
            hornMarkup.append("<polygon points=\"").append(num(x - 9)).append(",58 ").append(num(x)).append(",24 ")
                    .append(num(x + 9)).append(",58\" fill=\"").append(dark).append("\"/>");
        }
        String mouth = fangs
                ? "<path d=\"M72 146Q100 168 128 146Q100 156 72 146Z\" fill=\"" + dark + "\"/><polygon points=\"82,150 88,164 94,153\" fill=\"#fff\"/><polygon points=\"106,153 112,164 118,150\" fill=\"#fff\"/>"
                : "<path d=\"M78 148Q100 166 122 148\" fill=\"none\" stroke=\"" + dark + "\" stroke-width=\"5\" stroke-linecap=\"round\"/>";
        String cheekMarkup = cheeks
                ? "<circle cx=\"56\" cy=\"136\" r=\"9\" fill=\"#ff8aa0\" opacity=\".55\"/><circle cx=\"144\" cy=\"136\" r=\"9\" fill=\"#ff8aa0\" opacity=\".55\"/>"
                : "";
        String extra = switch (element) {
            case "炎" -> "<path d=\"M100 8C112 26 124 30 116 48C108 40 104 40 100 28C96 40 92 40 84 48C76 30 90 26 100 8Z\" fill=\"" + light + "\"/>";
            case "土" -> "<circle cx=\"64\" cy=\"76\" r=\"6\" fill=\"" + dark + "\" opacity=\".35\"/><circle cx=\"138\" cy=\"170\" r=\"7\" fill=\"" + dark + "\" opacity=\".35\"/><circle cx=\"48\" cy=\"150\" r=\"5\" fill=\"" + dark + "\" opacity=\".35\"/>";
            case "風" -> "<path d=\"M30 60q16-14 32 0M150 50q16-14 32 0\" fill=\"none\" stroke=\"" + light + "\" stroke-width=\"5\" stroke-linecap=\"round\"/>";
            case "雷" -> "<polygon points=\"108,162 90,184 102,184 94,200 118,176 104,176\" fill=\"" + light + "\"/>";
            default -> "<circle cx=\"100\" cy=\"112\" r=\"74\" fill=\"none\" stroke=\"" + light + "\" stroke-width=\"3\" stroke-dasharray=\"4 8\"/>";
        };
        String feet = "<ellipse cx=\"72\" cy=\"188\" rx=\"20\" ry=\"9\" fill=\"" + dark + "\"/><ellipse cx=\"128\" cy=\"188\" rx=\"20\" ry=\"9\" fill=\"" + dark + "\"/>";
        String sparkle = "SSR".equals(rarity) || "SR".equals(rarity)
                ? "<path d=\"M168 30l4 10 10 4-10 4-4 10-4-10-10-4 10-4z\" fill=\"#fff6c9\"/><path d=\"M26 40l3 8 8 3-8 3-3 8-3-8-8-3 8-3z\" fill=\"#fff6c9\"/>"
                : "";

        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 200\">"
                + "<defs><radialGradient id=\"" + gid + "\" cx=\"40%\" cy=\"30%\" r=\"80%\"><stop offset=\"0\" stop-color=\"" + light + "\"/><stop offset=\".55\" stop-color=\"" + main + "\"/><stop offset=\"1\" stop-color=\"" + dark + "\"/></radialGradient></defs>"
                + "<g fill=\"url(#" + gid + ")\" stroke=\"" + dark + "\" stroke-width=\"3\" stroke-linejoin=\"round\" transform=\"rotate(" + hueShift + " 100 110)\">"
                + feet + hornMarkup + body + "</g>" + extra + eyeMarkup + cheekMarkup + mouth + sparkle + "</svg>";
    }

    /** JavaScriptの数値の文字列化と同じ形（整数は小数点なし）にする。 */
    private static String num(double value) {
        if (value == Math.rint(value)) return Long.toString((long) value);
        return Double.toString(value);
    }
}
