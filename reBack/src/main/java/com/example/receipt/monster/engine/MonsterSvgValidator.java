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
 * 生成AIが返したSVGを許可リスト方式で検証する（要件定義書 13章）。
 * スクリプト・外部参照・イベント属性・スタイル要素・文字要素は拒否する。通らなければ代替イラストを使う。
 */
public final class MonsterSvgValidator {
    static final int MAX_LENGTH = 30_000;
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
            return isSafeElement(root);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isSafeElement(Element element) {
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
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            switch (child.getNodeType()) {
                case Node.ELEMENT_NODE -> {
                    if (!isSafeElement((Element) child)) return false;
                }
                case Node.TEXT_NODE -> {
                    if (!child.getNodeValue().isBlank()) return false;
                }
                case Node.COMMENT_NODE -> {
                    // コメントは表示に影響しない
                }
                default -> {
                    return false;
                }
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
