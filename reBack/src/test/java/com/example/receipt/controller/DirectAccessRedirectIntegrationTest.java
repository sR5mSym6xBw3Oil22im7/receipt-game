package com.example.receipt.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /admin/ 以外のページへの直接アクセスをメニュー（reFront/index.html）へ転送することの確認。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:receipt-direct-access;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DirectAccessRedirectIntegrationTest {
    private static final String MENU = "https://sr5msym6xbw3oil22im7.github.io/receipt-game/reFront/index.html";

    @Autowired MockMvc mockMvc;

    private static MockHttpServletRequestBuilder navigate(String path, String site) {
        return get(path).header("Sec-Fetch-Mode", "navigate").header("Sec-Fetch-Site", site);
    }

    @Test
    void directAccessToNonAdminPagesGoesToMenu() throws Exception {
        for (String path : new String[]{"/game/index.html", "/demo/index.html", "/", "/game/game.js", "/theme/theme.js", "/no-such-page.html", "/api/health"}) {
            mockMvc.perform(navigate(path, "none"))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl(MENU))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
    }

    @Test
    void linksFromOtherSitesGoToMenu() throws Exception {
        mockMvc.perform(navigate("/game/index.html", "cross-site").header("Referer", "https://evil.example/"))
                .andExpect(redirectedUrl(MENU));
        mockMvc.perform(navigate("/game/index.html", "cross-site")).andExpect(redirectedUrl(MENU));
    }

    @Test
    void linksFromMenuSiteAndSameOriginAreShown() throws Exception {
        mockMvc.perform(navigate("/game/index.html", "cross-site").header("Referer", "https://sr5msym6xbw3oil22im7.github.io/"))
                .andExpect(status().isOk());
        mockMvc.perform(navigate("/demo/index.html", "same-origin")).andExpect(status().isOk());
    }

    @Test
    void browsersWithoutFetchMetadataAreJudgedByAcceptAndReferer() throws Exception {
        mockMvc.perform(get("/game/index.html").header("Accept", "text/html")).andExpect(redirectedUrl(MENU));
        mockMvc.perform(get("/game/index.html").header("Accept", "text/html").header("Referer", "https://sr5msym6xbw3oil22im7.github.io/"))
                .andExpect(status().isOk());
    }

    @Test
    void subresourcesAndApiCallsAreNotRedirected() throws Exception {
        mockMvc.perform(get("/game/game.js").header("Sec-Fetch-Mode", "no-cors").header("Sec-Fetch-Site", "same-origin")).andExpect(status().isOk());
        mockMvc.perform(get("/api/health").header("Sec-Fetch-Mode", "cors").header("Sec-Fetch-Site", "same-origin")).andExpect(status().isOk());
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void adminPagesKeepTheirCurrentBehavior() throws Exception {
        mockMvc.perform(navigate("/admin/login.html", "none")).andExpect(status().isOk());
        mockMvc.perform(navigate("/admin/menu.html", "none")).andExpect(redirectedUrl("/admin/login.html"));
    }
}
