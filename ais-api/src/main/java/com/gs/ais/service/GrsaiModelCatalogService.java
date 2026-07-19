package com.gs.ais.service;

import com.gs.ais.dto.catalog.GrsaiModelCatalogItem;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GrsaiModelCatalogService {

    public static final String PROVIDER_KEY = "grsai";
    public static final String PROVIDER_NAME = "Grsai";
    public static final String DEFAULT_BASE_URL = "https://grsai.dakka.com.cn";
    public static final String MODELS_PAGE_URL = "https://grsai.ai/zh/dashboard/models";
    public static final String ADAPTER_TYPE = "GRS_AI";

    private static final Pattern MODEL_PATTERN = Pattern.compile(
            "(?<![a-z0-9-])((?:gpt-image|nano-banana)[a-z0-9.-]*)(?![a-z0-9-])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CNY_RANGE_PATTERN = Pattern.compile(
            "¥\\s*([0-9]+(?:\\.[0-9]+)?)(?:\\s*[-~至]\\s*¥?\\s*([0-9]+(?:\\.[0-9]+)?))?");
    private static final Pattern CREDIT_RANGE_PATTERN = Pattern.compile(
            "([0-9]+)(?:\\s*[-~至]\\s*([0-9]+))?\\s*点");

    private final RestTemplate restTemplate;
    private volatile List<GrsaiModelCatalogItem> catalog = builtInCatalog();

    public GrsaiModelCatalogService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.restTemplate = new RestTemplate(factory);
    }

    public List<GrsaiModelCatalogItem> getCatalog() {
        return catalog;
    }

    /**
     * Refreshes the public model list. If the website format changes, callers get
     * a clear error while the last known built-in catalog remains available.
     */
    public synchronized List<GrsaiModelCatalogItem> refresh() {
        RequestEntity<Void> request = RequestEntity.get(URI.create(MODELS_PAGE_URL))
                .accept(MediaType.TEXT_HTML)
                .header("User-Agent", "ais-grsai-catalog/1.0")
                .build();
        String html = restTemplate.exchange(request, String.class).getBody();
        List<GrsaiModelCatalogItem> parsed = parseCatalogPage(html);
        if (parsed.isEmpty()) {
            throw new IllegalStateException("未能从 Grsai 模型页面识别支持文生图或图生图的模型，已保留内置候选列表");
        }
        catalog = List.copyOf(parsed);
        return catalog;
    }

    List<GrsaiModelCatalogItem> parseCatalogPage(String html) {
        if (html == null || html.isBlank()) return List.of();
        String text = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&yen;", "¥")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ");

        Map<String, GrsaiModelCatalogItem> builtIns = builtInCatalog().stream()
                .collect(LinkedHashMap::new, (map, item) -> map.put(item.modelName(), item), Map::putAll);
        LinkedHashMap<String, GrsaiModelCatalogItem> result = new LinkedHashMap<>();
        Matcher matcher = MODEL_PATTERN.matcher(text);
        while (matcher.find()) {
            String modelName = matcher.group(1).toLowerCase(Locale.ROOT);
            int from = Math.max(0, matcher.start() - 120);
            int to = Math.min(text.length(), matcher.end() + 700);
            String nearby = text.substring(from, to);
            boolean textToImage = nearby.contains("文生图");
            boolean imageToImage = nearby.contains("图生图");
            if (!textToImage && !imageToImage) continue;

            GrsaiModelCatalogItem fallback = builtIns.get(modelName);
            Integer creditsMin = fallback == null ? null : fallback.priceCreditsMin();
            Integer creditsMax = fallback == null ? null : fallback.priceCreditsMax();
            BigDecimal cnyMin = fallback == null ? null : fallback.priceCnyMin();
            BigDecimal cnyMax = fallback == null ? null : fallback.priceCnyMax();

            Matcher credits = CREDIT_RANGE_PATTERN.matcher(nearby);
            if (credits.find()) {
                creditsMin = Integer.valueOf(credits.group(1));
                creditsMax = credits.group(2) == null ? creditsMin : Integer.valueOf(credits.group(2));
            }
            Matcher price = CNY_RANGE_PATTERN.matcher(nearby);
            if (price.find()) {
                cnyMin = new BigDecimal(price.group(1));
                cnyMax = price.group(2) == null ? cnyMin : new BigDecimal(price.group(2));
            }
            result.putIfAbsent(modelName, item(modelName,
                    fallback == null ? modelName : fallback.displayName(),
                    modelName.startsWith("gpt-image") ? "GPT Image" : "Nano Banana",
                    creditsMin, creditsMax, cnyMin, cnyMax));
        }
        return new ArrayList<>(result.values());
    }

    public static List<GrsaiModelCatalogItem> builtInCatalog() {
        return List.of(
                item("gpt-image-2", "GPT Image 2", "GPT Image", 5, 15, "0.050", "0.150"),
                item("gpt-image-2-vip", "GPT Image 2 VIP", "GPT Image", 9, 9, "0.090", "0.090"),
                item("gpt-image-1.5", "GPT Image 1.5", "GPT Image", 5, 15, "0.050", "0.150"),
                item("gpt-image-1.5-vip", "GPT Image 1.5 VIP", "GPT Image", 9, 9, "0.090", "0.090"),
                item("gpt-image-1-all", "GPT Image 1 All", "GPT Image", 2, 12, "0.020", "0.120"),
                item("nano-banana-2", "Nano Banana 2", "Nano Banana", 3, 12, "0.030", "0.120"),
                item("nano-banana-2-vip", "Nano Banana 2 VIP", "Nano Banana", 5, 18, "0.050", "0.180"),
                item("nano-banana-pro", "Nano Banana Pro", "Nano Banana", 4, 16, "0.040", "0.160"),
                item("nano-banana-pro-vip", "Nano Banana Pro VIP", "Nano Banana", 7, 24, "0.070", "0.240"),
                item("nano-banana-fast", "Nano Banana Fast", "Nano Banana", 1, 1, "0.010", "0.010"),
                item("nano-banana", "Nano Banana", "Nano Banana", 2, 2, "0.020", "0.020"),
                item("nano-banana-vip", "Nano Banana VIP", "Nano Banana", 3, 3, "0.030", "0.030")
        );
    }

    private static GrsaiModelCatalogItem item(String modelName, String displayName, String family,
                                               Integer creditsMin, Integer creditsMax,
                                               String cnyMin, String cnyMax) {
        return item(modelName, displayName, family, creditsMin, creditsMax,
                new BigDecimal(cnyMin), new BigDecimal(cnyMax));
    }

    private static GrsaiModelCatalogItem item(String modelName, String displayName, String family,
                                               Integer creditsMin, Integer creditsMax,
                                               BigDecimal cnyMin, BigDecimal cnyMax) {
        String credits = creditsMin == null ? "" : creditsMin.equals(creditsMax)
                ? creditsMin + " 点" : creditsMin + "-" + creditsMax + " 点";
        String price = cnyMin == null ? "" : cnyMin.compareTo(cnyMax) == 0
                ? "¥" + cnyMin.setScale(3) : "¥" + cnyMin.setScale(3) + "-" + cnyMax.setScale(3);
        String description = credits.isBlank() ? price : price.isBlank() ? credits : credits + " / " + price;
        return new GrsaiModelCatalogItem(modelName, displayName, family,
                true, true, creditsMin, creditsMax, cnyMin, cnyMax, description);
    }
}
