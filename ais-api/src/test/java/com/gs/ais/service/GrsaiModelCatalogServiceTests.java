package com.gs.ais.service;

import com.gs.ais.dto.catalog.GrsaiModelCatalogItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrsaiModelCatalogServiceTests {

    private final GrsaiModelCatalogService service = new GrsaiModelCatalogService();

    @Test
    void builtInCatalogOnlyContainsTextOrImageGenerationModelsWithPrices() {
        List<GrsaiModelCatalogItem> catalog = service.getCatalog();

        assertTrue(catalog.stream().anyMatch(item -> item.modelName().equals("gpt-image-2")));
        assertTrue(catalog.stream().anyMatch(item -> item.modelName().equals("nano-banana-2")));
        assertTrue(catalog.stream().allMatch(item -> item.supportsTextToImage() || item.supportsImageToImage()));
        assertTrue(catalog.stream().allMatch(item -> item.priceCnyMin() != null
                && item.priceCnyMax() != null && !item.priceDescription().isBlank()));

        GrsaiModelCatalogItem fast = catalog.stream()
                .filter(item -> item.modelName().equals("nano-banana-fast"))
                .findFirst().orElseThrow();
        assertEquals(1, fast.priceCreditsMin());
        assertEquals(new BigDecimal("0.010"), fast.priceCnyMin());
    }

    @Test
    void parsesSupportedImageModelsAndIgnoresChatOnlyEntries() {
        String html = """
                <div><h3>nano-banana-new</h3><span>文生图</span><span>图生图</span>
                <span>2-6点</span><span>¥0.020-¥0.060</span></div>
                <div><h3>gpt-chat-only</h3><span>对话</span><span>¥0.100</span></div>
                """;

        List<GrsaiModelCatalogItem> parsed = service.parseCatalogPage(html);

        assertEquals(1, parsed.size());
        assertEquals("nano-banana-new", parsed.getFirst().modelName());
        assertEquals(2, parsed.getFirst().priceCreditsMin());
        assertEquals(6, parsed.getFirst().priceCreditsMax());
        assertEquals(new BigDecimal("0.020"), parsed.getFirst().priceCnyMin());
        assertEquals(new BigDecimal("0.060"), parsed.getFirst().priceCnyMax());
    }
}
