package com.example.receipt.monster.engine;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 生成AIが返したSVGを許可リスト方式で検証する（doc/17_ai_receipt_analysis_specification.html 7章）。
 * スクリプト・外部参照・イベント属性・スタイル要素・文字要素は拒否する。通らなければ代替イラストを使う。
 */
public final class MonsterSvgValidator {
    static final int MAX_LENGTH = 30_000;
    static final int MAX_ELEMENTS = 300;
    static final int MAX_DEPTH = 12;
    static final double MAX_SIZE = 1000;
    private static final Set<String> ELEMENTS = Set.of(
            "svg", "g", "defs", "path", "circle", "ellipse", "rect", "polygon", "polyline", "line",
            "linearGradient", "radialGradient", "stop");
    private static final Set<String> ATTRIBUTES = Set.of(
            "xmlns", "viewBox", "width", "height", "id", "d", "cx", "cy", "r", "rx", "ry", "x", "y", "x1", "y1", "x2", "y2",
            "fx", "fy", "points", "fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-opacity",
            "stroke-linecap", "stroke-linejoin", "stroke-dasharray", "opacity", "transform", "offset", "stop-color",
            "stop-opacity", "gradientUnits", "gradientTransform");
    private static final Pattern URL_REF = Pattern.compile("url\\(\\s*#[A-Za-z][\\w-]*\\s*\\)");
    private static final Pattern SAFE_VALUE = Pattern.compile("[#%(),.\\-\\s\\w]*");

    private MonsterSvgValidator() {
    }

    /** 安全なら true。 */
    public static boolean isSafe(String svg) {
        if (svg == null || svg.isBlank() || svg.length() > MAX_LENGTH) return false;
        String lower = svg.toLowerCase(Locale.ROOT);
        if (lower.contains("<!doctype") || lower.contains("<!entity") || lower.contains("<?") && !lower.startsWith("<?xml")
                || lower.contains("javascript:") || lower.contains("<![cdata[")) {
            return false;
        }
        try {
            Document doc = parser().parse(new InputSource(new StringReader(svg)));
            Element root = doc.getDocumentElement();
            if (!"svg".equals(root.getTagName()) || !root.hasAttribute("viewBox")) return false;
            return isSafeElement(root, 1, new int[1]);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isSafeElement(Element element, int depth, int[] count) {
        // 描画負荷の高いSVG（極端に深い・多い）を拒否する
        if (depth > MAX_DEPTH || ++count[0] > MAX_ELEMENTS) return false;
        if (!ELEMENTS.contains(element.getTagName())) return false;
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String name = attribute.getNodeName();
            if (!ATTRIBUTES.contains(name)) return false;
            if ("xmlns".equals(name)) {
                if (!"http://www.w3.org/2000/svg".equals(attribute.getNodeValue())) return false;
                continue;
            }
            String value = attribute.getNodeValue();
            String withoutRefs = URL_REF.matcher(value).replaceAll("");
            if (withoutRefs.toLowerCase(Locale.ROOT).contains("url(") || !SAFE_VALUE.matcher(withoutRefs).matches()) return false;
            if (("width".equals(name) || "height".equals(name)) && !isNumberAtMost(value, MAX_SIZE)) return false;
            if ("stroke-dasharray".equals(name) && !isDashArrayCoarse(value)) return false;
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            switch (child.getNodeType()) {
                case Node.ELEMENT_NODE -> {
                    if (!isSafeElement((Element) child, depth + 1, count)) return false;
                }
                case Node.TEXT_NODE -> {
                    if (!child.getNodeValue().isBlank()) return false;
                }
                case Node.COMMENT_NODE -> {
                    // コメントは不要で、任意の文字列（<script> など）を持ち込めるため拒否する
                    return false;
                }
                default -> {
                    return false;
                }
            }
        }
        return true;
    }

    /** 数値（単位なし、または%）で、max以下か。 */
    private static boolean isNumberAtMost(String value, double max) {
        try {
            double number = Double.parseDouble(value.trim().replace("%", ""));
            return number >= 0 && number <= max;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 点線の間隔が極端に小さい（描画が非常に重くなる）値を拒否する。各値は1以上。 */
    private static boolean isDashArrayCoarse(String value) {
        for (String part : value.trim().split("[\\s,]+")) {
            try {
                if (Double.parseDouble(part) < 1) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static DocumentBuilder parser() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler());
        return builder;
    }
}
