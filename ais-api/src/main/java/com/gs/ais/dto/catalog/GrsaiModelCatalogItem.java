package com.gs.ais.dto.catalog;

import java.math.BigDecimal;

public record GrsaiModelCatalogItem(
        String modelName,
        String displayName,
        String family,
        boolean supportsTextToImage,
        boolean supportsImageToImage,
        Integer priceCreditsMin,
        Integer priceCreditsMax,
        BigDecimal priceCnyMin,
        BigDecimal priceCnyMax,
        String priceDescription) {
}
