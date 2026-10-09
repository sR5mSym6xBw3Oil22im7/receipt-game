package com.example.receipt.monster.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class MonsterSvgValidatorTest {

    @Test
    void fallbackIllustrationsAreAccepted() {
        for (String element : new String[]{"炎", "土", "風", "雷", "無"}) {
            for (long seed = 0; seed < 40; seed++) {
                assertThat(MonsterSvgValidator.isSafe(MonsterSvgRenderer.fallbackSvg(element, "SSR", seed * 977_771L))).isTrue();
            }
        }
    }

    @Test
    void simpleShapesAreAccepted() {
        assertThat(MonsterSvgValidator.isSafe(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 200\"><circle cx=\"100\" cy=\"100\" r=\"50\" fill=\"#e4572e\"/></svg>"))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 200\"><script>alert(1)</script></svg>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 200\" onload=\"alert(1)\"></svg>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 200\"><image href=\"https://example.com/a.png\"/></svg>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 200\"><use href=\"#a\"/></svg>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 200\"><rect fill=\"url(https://example.com/x)\"/></svg>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 200\"><style>*{}</style></svg>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 200\"><text>hi</text></svg>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 200\"><foreignObject/></svg>",
            "<!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><svg viewBox=\"0 0 1 1\">&x;</svg>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><circle r=\"1\"/></svg>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 200\"><rect style=\"fill:red\"/></svg>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 200\"><rect fill=\"javascript:alert(1)\"/></svg>",
            "<html><svg/></html>",
            "not svg"
    })
    void unsafeOrInvalidSvgIsRejected(String svg) {
        assertThat(MonsterSvgValidator.isSafe(svg)).isFalse();
    }
}
