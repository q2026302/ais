package com.gs.ais.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gs.ais.dto.response.FetchModelsResponse;
import com.gs.ais.dto.response.TestConnectionResponse;
import com.gs.ais.model.ModelProviderDefaults;
import com.gs.ais.service.LlmDebugService;
import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.util.ApiRequestUrlUtils;
import com.gs.ais.util.LlmErrorMessageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.web.util.UriComponentsBuilder;

@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final RestTemplate restTemplate;
    private final LlmDebugService debugService;

    public LlmClient(LlmDebugService debugService) {
        this.debugService = debugService;
        this.restTemplate = createRestTemplate(10, ModelProviderDefaults.CHAT_TIMEOUT_SECONDS);
    }

    public record ChatResult(String content, Integer promptTokens,
                               Integer completionTokens, Integer totalTokens,
                               Integer cacheReadTokens, Integer cacheWriteTokens,
                               Integer reasoningTokens) {
        public ChatResult(String content, Integer promptTokens, Integer completionTokens, Integer totalTokens) {
            this(content, promptTokens, completionTokens, totalTokens, null, null, null);
        }
    }

    public record OptimizeResult(String prompt, Integer promptTokens,
                                   Integer completionTokens, Integer totalTokens,
                                   Integer cacheReadTokens, Integer cacheWriteTokens,
                                   Integer reasoningTokens) {
        public OptimizeResult(String prompt, Integer promptTokens, Integer completionTokens, Integer totalTokens) {
            this(prompt, promptTokens, completionTokens, totalTokens, null, null, null);
        }
    }

    public record ImageGenerationOptions(String size, String quality, String format) {}

    public record ReferenceImage(String filename, String contentType, byte[] data) {}

    /**
     * General-purpose chat method that sends multiple messages with full context.
     */
    public ChatResult chat(List<Map<String, Object>> messages, ModelProvider chatProvider) {
        String url = ApiRequestUrlUtils.endpoint(chatProvider.getBaseUrl(), "/chat/completions");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(chatProvider.getApiKey());

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", chatProvider.getModelName());
        requestBody.put("messages", messages);
        applyJsonConfig(requestBody, chatProvider.getConfigJson(), "chat", "model", "messages");
        String reasoningEffort = chatProvider.getReasoningEffort();
        if (reasoningEffort != null && !reasoningEffort.isBlank()
                && !"default".equalsIgnoreCase(reasoningEffort)
                && !"auto".equalsIgnoreCase(reasoningEffort)) {
            requestBody.put("reasoning_effort", reasoningEffort);
        }
        applyTemperature(requestBody, chatProvider);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        log.debug("Calling chat API with {} messages at {}", messages.size(), url);
        ResponseEntity<Map> response = templateFor(chatProvider).postForEntity(url, request, Map.class);

        if (response.getBody() == null) {
            throw new RuntimeException("Chat API returned empty response");
        }
        throwIfProviderError(response.getBody());

        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("Chat API returned no choices");
        }

        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        String content = (String) message.get("content");

        Integer promptTokens = null, completionTokens = null, totalTokens = null;
        Integer cacheReadTokens = null, cacheWriteTokens = null, reasoningTokens = null;
        Map<String, Object> usage = (Map<String, Object>) response.getBody().get("usage");
        if (usage != null) {
            promptTokens = usageNumber(usage, "prompt_tokens", "input_tokens");
            completionTokens = usageNumber(usage, "completion_tokens", "output_tokens");
            totalTokens = usageNumber(usage, "total_tokens");
            cacheReadTokens = nestedUsageNumber(usage, "prompt_tokens_details", "cached_tokens");
            if (cacheReadTokens == null) cacheReadTokens = nestedUsageNumber(usage, "input_tokens_details", "cache_read");
            cacheWriteTokens = nestedUsageNumber(usage, "prompt_tokens_details", "cache_creation_tokens");
            if (cacheWriteTokens == null) cacheWriteTokens = nestedUsageNumber(usage, "input_tokens_details", "cache_write");
            reasoningTokens = nestedUsageNumber(usage, "completion_tokens_details", "reasoning_tokens");
            if (totalTokens == null && promptTokens != null && completionTokens != null) totalTokens = promptTokens + completionTokens;
        }

        String result = content != null ? content.trim() : "";
        return new ChatResult(result, promptTokens, completionTokens, totalTokens,
                cacheReadTokens, cacheWriteTokens, reasoningTokens);
    }

    /**
     * Call chat model to optimize a user's image description into a high-quality
     * English image generation prompt.
     */
    public OptimizeResult optimizePrompt(String userInput, ModelProvider chatProvider) {
        String url = ApiRequestUrlUtils.endpoint(chatProvider.getBaseUrl(), "/chat/completions");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(chatProvider.getApiKey());

        String systemPrompt = """
                You are an expert at writing image generation prompts. \
                Given a user's description, create a detailed, high-quality English prompt \
                for an image generation model. Include details about style, lighting, composition, \
                and mood. Return ONLY the prompt text, nothing else.
                """;

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", chatProvider.getModelName());
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userInput)));
        applyJsonConfig(requestBody, chatProvider.getConfigJson(), "chat", "model", "messages");
        applyTemperature(requestBody, chatProvider);
        String reasoningEffort = chatProvider.getReasoningEffort();
        if (hasText(reasoningEffort)
                && !"default".equalsIgnoreCase(reasoningEffort)
                && !"auto".equalsIgnoreCase(reasoningEffort)) {
            requestBody.put("reasoning_effort", reasoningEffort);
        }

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        log.debug("Calling chat API at {} with model {}", url, chatProvider.getModelName());
        ResponseEntity<Map> response = templateFor(chatProvider).postForEntity(url, request, Map.class);

        if (response.getBody() == null) {
            throw new RuntimeException("Chat API returned empty response");
        }
        throwIfProviderError(response.getBody());

        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("Chat API returned no choices");
        }

        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        String content = (String) message.get("content");

        // Parse token usage
        Integer promptTokens = null;
        Integer completionTokens = null;
        Integer totalTokens = null;
        Integer cacheReadTokens = null, cacheWriteTokens = null, reasoningTokens = null;
        Map<String, Object> usage = (Map<String, Object>) response.getBody().get("usage");
        if (usage != null) {
            promptTokens = usageNumber(usage, "prompt_tokens", "input_tokens");
            completionTokens = usageNumber(usage, "completion_tokens", "output_tokens");
            totalTokens = usageNumber(usage, "total_tokens");
            cacheReadTokens = nestedUsageNumber(usage, "prompt_tokens_details", "cached_tokens");
            if (cacheReadTokens == null) cacheReadTokens = nestedUsageNumber(usage, "input_tokens_details", "cache_read");
            cacheWriteTokens = nestedUsageNumber(usage, "prompt_tokens_details", "cache_creation_tokens");
            if (cacheWriteTokens == null) cacheWriteTokens = nestedUsageNumber(usage, "input_tokens_details", "cache_write");
            reasoningTokens = nestedUsageNumber(usage, "completion_tokens_details", "reasoning_tokens");
            if (totalTokens == null && promptTokens != null && completionTokens != null) totalTokens = promptTokens + completionTokens;
        }

        log.debug("Optimized prompt: {}", content);
        String result = content != null ? content.trim() : userInput;
        return new OptimizeResult(result, promptTokens, completionTokens, totalTokens,
                cacheReadTokens, cacheWriteTokens, reasoningTokens);
    }

    private Integer usageNumber(Map<String, Object> usage, String... keys) {
        for (String key : keys) {
            Object value = usage.get(key);
            if (value instanceof Number number) return number.intValue();
        }
        return null;
    }

    private Integer nestedUsageNumber(Map<String, Object> usage, String containerKey, String valueKey) {
        Object nested = usage.get(containerKey);
        if (nested instanceof Map<?, ?> map && map.get(valueKey) instanceof Number number) return number.intValue();
        return null;
    }

    /**
     * Generate an image using the image model provider.
     * Returns the generated image bytes. If the provider returns a URL instead of
     * inline image data, the URL is downloaded and the bytes are returned.
     */
    public byte[] generateImage(String prompt, ModelProvider imageProvider) {
        return generateImage(prompt, imageProvider, null, Collections.emptyList());
    }

    public byte[] generateImage(String prompt, ModelProvider imageProvider,
                                ImageGenerationOptions options,
                                List<ReferenceImage> referenceImages) {
        List<ReferenceImage> safeReferences = referenceImages == null
                ? Collections.emptyList()
                : referenceImages.stream()
                .filter(img -> img != null && img.data() != null && img.data().length > 0)
                .toList();

        if (isGrsaiImageAdapter(imageProvider)) {
            return generateGrsaiImage(prompt, imageProvider, options, safeReferences);
        }
        if (isGeminiImageAdapter(imageProvider)) {
            return generateGeminiImage(prompt, imageProvider, options, safeReferences);
        }
        if (!safeReferences.isEmpty()) {
            return generateImageEdit(prompt, imageProvider, options, safeReferences);
        }
        return generateImageGeneration(prompt, imageProvider, options);
    }

    private byte[] generateGrsaiImage(String prompt, ModelProvider provider,
                                      ImageGenerationOptions options,
                                      List<ReferenceImage> references) {
        String generateUrl = ApiRequestUrlUtils.rootEndpoint(provider.getBaseUrl(), "/v1/api/generate");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (hasText(provider.getApiKey())) headers.setBearerAuth(provider.getApiKey());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", provider.getModelName());
        body.put("prompt", prompt);
        body.put("images", references.stream().map(this::toDataUrl).toList());
        body.put("aspectRatio", grsaiAspectRatio(provider, options == null ? null : options.size()));
        if (isGrsaiNanoBananaModel(provider)) {
            String imageSize = grsaiImageSize(options == null ? null : options.quality(),
                    options == null ? null : options.size());
            if (hasText(imageSize)) body.put("imageSize", imageSize);
        }
        // The documented default is an explicit completed JSON response. Advanced
        // configurations can override this with async through configJson.
        body.put("replyType", "json");
        applyGrsaiConfig(body, provider.getConfigJson());
        String replyType = grsaiReplyType(body.get("replyType"));
        body.put("replyType", replyType);
        if ("stream".equals(replyType)) {
            throw new IllegalArgumentException(
                    "Grsai replyType=stream 暂不适用于当前服务端图片落盘流程，请使用 json 或 async");
        }

        RestTemplate template = templateFor(provider);
        ResponseEntity<Map> response = template.postForEntity(
                generateUrl, new HttpEntity<>(body, headers), Map.class);
        Map<?, ?> responseBody = requireGrsaiBody(response.getBody(), "生成");
        byte[] immediate = extractGrsaiImageBytes(responseBody, template);
        if (immediate != null) return immediate;

        String status = grsaiStatus(responseBody);
        if (isGrsaiFailedStatus(status)) {
            throw new RuntimeException("Grsai 图片生成失败（状态：" + status + "）："
                    + grsaiMessage(responseBody));
        }
        String taskId = grsaiTaskId(responseBody);
        if (!"async".equals(replyType)) {
            throw new RuntimeException("Grsai replyType=" + replyType
                    + " 未返回图片结果（状态：" + status + "，任务 ID："
                    + (hasText(taskId) ? taskId : "无") + "）");
        }
        if (!hasText(taskId)) {
            throw new RuntimeException("Grsai 异步图片生成接口未返回任务 ID");
        }
        return pollGrsaiImage(taskId, provider, headers, template);
    }

    private byte[] pollGrsaiImage(String taskId, ModelProvider provider,
                                  HttpHeaders headers, RestTemplate template) {
        String resultUrl = UriComponentsBuilder.fromUriString(
                        ApiRequestUrlUtils.rootEndpoint(provider.getBaseUrl(), "/v1/api/result"))
                .queryParam("id", taskId)
                .build().encode().toUriString();
        long intervalMillis = grsaiPollIntervalMillis(provider.getConfigJson());
        Instant deadline = Instant.now().plusSeconds(ModelProviderDefaults.timeoutSeconds(provider));
        String lastStatus = "submitted";

        while (Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("等待 Grsai 图片生成结果时任务被中断", e);
            }
            ResponseEntity<Map> response = template.exchange(
                    resultUrl, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            Map<?, ?> responseBody = requireGrsaiBody(response.getBody(), "任务查询");
            byte[] image = extractGrsaiImageBytes(responseBody, template);
            if (image != null) return image;
            lastStatus = grsaiStatus(responseBody);
            if (isGrsaiFailedStatus(lastStatus)) {
                throw new RuntimeException("Grsai 图片生成失败（状态：" + lastStatus + "）："
                        + grsaiMessage(responseBody));
            }
        }
        throw new RuntimeException("等待 Grsai 图片生成超时，任务 " + taskId + " 最后状态为 " + lastStatus);
    }

    private Map<?, ?> requireGrsaiBody(Map<?, ?> responseBody, String operation) {
        if (responseBody == null) throw new RuntimeException("Grsai " + operation + "接口返回空响应");
        Object code = responseBody.get("code");
        if (code instanceof Number number && number.intValue() != 0) {
            throw new RuntimeException("Grsai " + operation + "失败（code=" + number.intValue()
                    + "）：" + grsaiMessage(responseBody));
        }
        throwIfProviderError(responseBody);
        return responseBody;
    }

    private String grsaiTaskId(Map<?, ?> responseBody) {
        Object data = responseBody.get("data");
        if (data instanceof Map<?, ?> map) {
            Object id = map.get("id");
            return id == null ? null : String.valueOf(id);
        }
        Object id = responseBody.get("id");
        return id == null ? null : String.valueOf(id);
    }

    private String grsaiStatus(Map<?, ?> responseBody) {
        Object data = responseBody.get("data");
        Object status = data instanceof Map<?, ?> map ? map.get("status") : responseBody.get("status");
        return status == null ? "unknown" : String.valueOf(status).trim().toLowerCase(Locale.ROOT);
    }

    private boolean isGrsaiFailedStatus(String status) {
        return status.contains("fail") || status.contains("error")
                || status.contains("cancel") || status.contains("reject");
    }

    private String grsaiMessage(Map<?, ?> responseBody) {
        for (String key : List.of("msg", "message", "error")) {
            Object value = responseBody.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        Object data = responseBody.get("data");
        if (data instanceof Map<?, ?> map) {
            for (String key : List.of("msg", "message", "error", "failReason")) {
                Object value = map.get(key);
                if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
            }
        }
        return "上游未提供详细错误信息";
    }

    private byte[] extractGrsaiImageBytes(Map<?, ?> responseBody, RestTemplate template) {
        List<Map<?, ?>> resultContainers = new ArrayList<>();
        Object data = responseBody.get("data");
        if (data instanceof Map<?, ?> dataMap) resultContainers.add(dataMap);
        // Grsai may return the task fields directly at the top level instead of
        // wrapping them in data, for example: {id, status, results:[{url}]}.
        resultContainers.add(responseBody);

        List<Object> candidates = new ArrayList<>();
        for (Map<?, ?> container : resultContainers) {
            for (String key : List.of("results", "images", "urls")) {
                Object value = container.get(key);
                if (value instanceof List<?> list) candidates.addAll(list);
                else if (value != null) candidates.add(value);
            }
            for (String key : List.of("url", "imageUrl", "image", "base64", "b64_json")) {
                Object value = container.get(key);
                if (value != null) candidates.add(value);
            }
        }
        for (Object candidate : candidates) {
            byte[] image = decodeGrsaiCandidate(candidate, template);
            if (image != null) return image;
        }
        if ("succeeded".equals(grsaiStatus(responseBody)) || "success".equals(grsaiStatus(responseBody))) {
            throw new RuntimeException("Grsai 任务已成功，但响应中没有图片数据");
        }
        return null;
    }

    private byte[] decodeGrsaiCandidate(Object candidate, RestTemplate template) {
        if (candidate instanceof Map<?, ?> map) {
            for (String key : List.of("url", "imageUrl", "image", "base64", "b64_json", "content")) {
                Object nested = map.get(key);
                byte[] image = decodeGrsaiCandidate(nested, template);
                if (image != null) return image;
            }
            return null;
        }
        if (!(candidate instanceof String value) || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.startsWith("data:image/")) return decodeBase64Image(trimmed);
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            ResponseEntity<byte[]> response = template.getForEntity(trimmed, byte[].class);
            return response.getBody() != null && response.getBody().length > 0 ? response.getBody() : null;
        }
        if (trimmed.length() > 128 && trimmed.matches("[A-Za-z0-9+/=\r\n]+")) {
            return decodeBase64Image(trimmed);
        }
        return null;
    }

    private String toDataUrl(ReferenceImage image) {
        String contentType = hasText(image.contentType()) ? image.contentType() : MediaType.IMAGE_PNG_VALUE;
        return "data:" + contentType + ";base64,"
                + java.util.Base64.getEncoder().encodeToString(image.data());
    }

    private String grsaiAspectRatio(ModelProvider provider, String size) {
        if (!hasText(size) || "auto".equalsIgnoreCase(size)) return "auto";
        String normalized = size.trim().toLowerCase(Locale.ROOT)
                .replace('×', 'x').replace('*', 'x');
        if (isGrsaiGptImageModel(provider)) return normalized;
        if (normalized.contains(":")) return normalized;
        return switch (normalized) {
            case "1024x1024", "512x512", "768x768" -> "1:1";
            case "1536x1024", "1792x1024" -> "3:2";
            case "1024x1536", "1024x1792" -> "2:3";
            default -> "auto";
        };
    }

    private boolean isGrsaiGptImageModel(ModelProvider provider) {
        return provider != null && hasText(provider.getModelName())
                && provider.getModelName().trim().toLowerCase(Locale.ROOT).startsWith("gpt-image");
    }

    private boolean isGrsaiNanoBananaModel(ModelProvider provider) {
        return provider != null && hasText(provider.getModelName())
                && provider.getModelName().trim().toLowerCase(Locale.ROOT).startsWith("nano-banana");
    }

    private String grsaiReplyType(Object value) {
        String replyType = value == null ? "json" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (!Set.of("json", "stream", "async").contains(replyType)) {
            throw new IllegalArgumentException(
                    "Grsai replyType 仅支持 json、stream、async，当前值为：" + replyType);
        }
        return replyType;
    }

    private String grsaiImageSize(String quality, String size) {
        if (hasText(quality)) {
            String normalized = quality.trim().toUpperCase(Locale.ROOT);
            if (normalized.matches("[124]K")) return normalized;
        }
        if (!hasText(size) || !size.toLowerCase(Locale.ROOT).contains("x")) return null;
        try {
            String[] parts = size.toLowerCase(Locale.ROOT).replace('×', 'x').split("x");
            int maximum = Math.max(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            if (maximum <= 1024) return "1K";
            if (maximum <= 2048) return "2K";
            return "4K";
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void applyGrsaiConfig(Map<String, Object> body, String configJson) {
        if (!hasText(configJson)) return;
        Map<String, Object> config = parseConfigJson(configJson, "Grsai image");
        Set<String> reserved = Set.of("model", "prompt", "images", "urls", "imageType", "grsaiPollIntervalMillis");
        config.forEach((key, value) -> {
            if (!reserved.contains(key)) body.put(key, value);
        });
    }

    private long grsaiPollIntervalMillis(String configJson) {
        if (!hasText(configJson)) return 1500L;
        Object value = parseConfigJson(configJson, "Grsai image").get("grsaiPollIntervalMillis");
        if (value instanceof Number number) return Math.max(100L, Math.min(10_000L, number.longValue()));
        return 1500L;
    }

    private byte[] generateGeminiImage(String prompt, ModelProvider provider,
                                       ImageGenerationOptions options,
                                       List<ReferenceImage> references) {
        String endpoint = "/models/" + provider.getModelName() + ":generateContent";
        String baseUrl = geminiApiBaseUrl(provider.getBaseUrl());
        boolean officialEndpoint = isOfficialGeminiEndpoint(baseUrl);
        String url = ApiRequestUrlUtils.endpoint(baseUrl, endpoint);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (hasText(provider.getApiKey())) {
            if (officialEndpoint) {
                headers.set("x-goog-api-key", provider.getApiKey());
            } else {
                headers.setBearerAuth(provider.getApiKey());
            }
        }

        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", prompt));
        for (ReferenceImage image : references) {
            // Google's REST API documents lowerCamelCase while UU API's gateway
            // example uses protobuf snake_case. Send the form native to each endpoint.
            String inlineDataKey = officialEndpoint ? "inlineData" : "inline_data";
            String mimeTypeKey = officialEndpoint ? "mimeType" : "mime_type";
            parts.add(Map.of(inlineDataKey, Map.of(
                    mimeTypeKey, hasText(image.contentType()) ? image.contentType() : "image/png",
                    "data", java.util.Base64.getEncoder().encodeToString(image.data()))));
        }

        Map<String, Object> imageConfig = new LinkedHashMap<>();
        if (options != null && hasText(options.size()) && options.size().contains(":")) {
            imageConfig.put("aspectRatio", options.size());
        }
        if (options != null && hasText(options.quality())
                && options.quality().toUpperCase().matches("[124]K")) {
            imageConfig.put("imageSize", options.quality().toUpperCase());
        }
        applyGeminiExtraConfig(provider.getConfigJson(), imageConfig);

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("responseModalities", List.of("TEXT", "IMAGE"));
        if (!imageConfig.isEmpty()) generationConfig.put("imageConfig", imageConfig);
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("role", "user", "parts", parts)),
                "generationConfig", generationConfig);

        ResponseEntity<Map> response = templateFor(provider).postForEntity(
                url, new HttpEntity<>(body, headers), Map.class);
        return extractGeminiImageBytes(response);
    }

    private void applyGeminiExtraConfig(String configJson, Map<String, Object> imageConfig) {
        if (!hasText(configJson)) return;
        try {
            Map<String, Object> config = OBJECT_MAPPER.readValue(configJson, new TypeReference<>() {});
            Object nested = config.get("imageConfig");
            if (nested instanceof Map<?, ?> map) {
                map.forEach((key, value) -> imageConfig.put(String.valueOf(key), value));
            } else {
                config.forEach((key, value) -> {
                    if (!"responseModalities".equals(key)) imageConfig.put(key, value);
                });
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid image provider config JSON: " + e.getMessage(), e);
        }
    }

    private byte[] extractGeminiImageBytes(ResponseEntity<Map> response) {
        if (response.getBody() == null) throw new RuntimeException("Gemini image API returned empty response");
        throwIfProviderError(response.getBody());
        Object candidatesValue = response.getBody().get("candidates");
        if (!(candidatesValue instanceof List<?> candidates)) {
            throw new RuntimeException("Gemini image API returned no candidates");
        }
        for (Object candidateValue : candidates) {
            if (!(candidateValue instanceof Map<?, ?> candidate)) continue;
            Object contentValue = candidate.get("content");
            if (!(contentValue instanceof Map<?, ?> content)) continue;
            Object partsValue = content.get("parts");
            if (!(partsValue instanceof List<?> responseParts)) continue;
            for (Object partValue : responseParts) {
                if (!(partValue instanceof Map<?, ?> part)) continue;
                Object inlineValue = part.containsKey("inlineData") ? part.get("inlineData") : part.get("inline_data");
                if (!(inlineValue instanceof Map<?, ?> inline)) continue;
                Object data = inline.get("data");
                if (data instanceof String encoded && !encoded.isBlank()) {
                    return decodeBase64Image(encoded);
                }
            }
        }
        throw new RuntimeException("Gemini image API returned no image data");
    }

    private byte[] generateImageGeneration(String prompt, ModelProvider imageProvider, ImageGenerationOptions options) {
        String url = ApiRequestUrlUtils.endpoint(imageProvider.getBaseUrl(), "/images/generations");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (imageProvider.getApiKey() != null && !imageProvider.getApiKey().isEmpty()) {
            headers.setBearerAuth(imageProvider.getApiKey());
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", imageProvider.getModelName());
        requestBody.put("prompt", prompt);
        applyJsonConfig(requestBody, imageProvider.getConfigJson(), "image", "model", "prompt", "image");
        requestBody.putIfAbsent("n", 1);
        applyImageOptions(requestBody, imageProvider, options);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        log.debug("Calling image API at {} with model {}", url, imageProvider.getModelName());
        ResponseEntity<Map> response = templateFor(imageProvider).postForEntity(url, request, Map.class);
        return extractImageBytes(response);
    }

    private byte[] generateImageEdit(String prompt, ModelProvider imageProvider,
                                     ImageGenerationOptions options,
                                     List<ReferenceImage> referenceImages) {
        String url = ApiRequestUrlUtils.endpoint(imageProvider.getBaseUrl(), "/images/edits");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (imageProvider.getApiKey() != null && !imageProvider.getApiKey().isEmpty()) {
            headers.setBearerAuth(imageProvider.getApiKey());
        }

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("model", imageProvider.getModelName());
        body.add("prompt", prompt);
        applyMultipartJsonConfig(body, imageProvider.getConfigJson(), "model", "prompt", "image");
        if (!body.containsKey("n")) body.add("n", "1");
        applyMultipartImageOptions(body, imageProvider, options);

        for (ReferenceImage image : referenceImages) {
            NamedByteArrayResource resource = new NamedByteArrayResource(image.data(), image.filename(), image.contentType());
            HttpHeaders imageHeaders = new HttpHeaders();
            imageHeaders.setContentType(MediaType.parseMediaType(resource.getContentType()));
            body.add("image", new HttpEntity<>(resource, imageHeaders));
        }

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);
        log.debug("Calling image edit API at {} with {} reference image(s)", url, referenceImages.size());
        ResponseEntity<Map> response = templateFor(imageProvider).postForEntity(url, request, Map.class);
        return extractImageBytes(response);
    }

    private void applyImageOptions(Map<String, Object> requestBody,
                                   ModelProvider imageProvider,
                                   ImageGenerationOptions options) {
        boolean gptImage = isGptImageModel(imageProvider);
        if (hasText(options != null ? options.size() : null)) {
            requestBody.put("size", options.size());
        }
        if (hasText(options != null ? options.quality() : null)) {
            requestBody.put("quality", options.quality());
        }
        if (gptImage && hasText(options != null ? options.format() : null)) {
            requestBody.put("output_format", options.format());
        }
        // UU/OpenAI-compatible image gateways conventionally return base64 in data[0].b64_json.
        // Preserve an explicit provider override (for example response_format=url).
        requestBody.putIfAbsent("response_format", "b64_json");
    }

    private void applyMultipartImageOptions(MultiValueMap<String, Object> body,
                                            ModelProvider imageProvider,
                                            ImageGenerationOptions options) {
        boolean gptImage = isGptImageModel(imageProvider);
        if (hasText(options != null ? options.size() : null)) {
            body.set("size", options.size());
        }
        if (hasText(options != null ? options.quality() : null)) {
            body.set("quality", options.quality());
        }
        if (gptImage && hasText(options != null ? options.format() : null)) {
            body.set("output_format", options.format());
        }
        if (!body.containsKey("response_format")) {
            body.add("response_format", "b64_json");
        }
    }

    private void applyTemperature(Map<String, Object> requestBody, ModelProvider provider) {
        if (provider != null && provider.getTemperature() != null) {
            requestBody.put("temperature", provider.getTemperature());
            return;
        }
        Double defaultTemperature = ModelProviderDefaults.temperature(provider);
        if (defaultTemperature != null) {
            requestBody.putIfAbsent("temperature", defaultTemperature);
        }
    }

    private void applyJsonConfig(Map<String, Object> target,
                                 String configJson,
                                 String context,
                                 String... reservedKeys) {
        if (!hasText(configJson)) return;
        Map<String, Object> config = parseConfigJson(configJson, context);
        Set<String> reserved = Set.of(reservedKeys);
        config.forEach((key, value) -> {
            if (!reserved.contains(key)) target.put(key, value);
        });
    }

    private void applyMultipartJsonConfig(MultiValueMap<String, Object> target,
                                          String configJson,
                                          String... reservedKeys) {
        if (!hasText(configJson)) return;
        Map<String, Object> config = parseConfigJson(configJson, "image");
        Set<String> reserved = Set.of(reservedKeys);
        config.forEach((key, value) -> {
            if (reserved.contains(key) || value == null) return;
            if (value instanceof List<?> values) {
                target.remove(key);
                values.forEach(item -> target.add(key, multipartValue(item)));
            } else {
                target.set(key, multipartValue(value));
            }
        });
    }

    private Object multipartValue(Object value) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                return OBJECT_MAPPER.writeValueAsString(value);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid multipart model parameter: " + e.getMessage(), e);
            }
        }
        return String.valueOf(value);
    }

    private Map<String, Object> parseConfigJson(String configJson, String context) {
        try {
            return OBJECT_MAPPER.readValue(configJson, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid " + context + " provider config JSON: " + e.getMessage(), e);
        }
    }

    private boolean isOfficialGeminiEndpoint(String baseUrl) {
        return baseUrl != null && baseUrl.toLowerCase().contains("generativelanguage.googleapis.com");
    }

    private String geminiApiBaseUrl(String configuredBaseUrl) {
        String normalized = ApiRequestUrlUtils.normalizeBaseUrl(configuredBaseUrl);
        if (!hasText(normalized)) return normalized;

        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/v1")) {
            return normalized.substring(0, normalized.length() - 3) + "/v1beta";
        }
        // Respect an explicitly configured Gemini API version such as v1beta or v1alpha.
        if (lower.matches(".*/v\\d+(?:alpha\\d*|beta\\d*)?$")) {
            return normalized;
        }
        return normalized + "/v1beta";
    }

    private byte[] decodeBase64Image(String encoded) {
        String value = encoded != null ? encoded.trim() : "";
        int comma = value.indexOf(',');
        if (value.startsWith("data:") && comma >= 0) {
            value = value.substring(comma + 1);
        }
        return java.util.Base64.getMimeDecoder().decode(value);
    }

    private boolean isGrsaiImageAdapter(ModelProvider provider) {
        String adapter = provider != null && provider.getAdapterType() != null
                ? provider.getAdapterType().trim().toUpperCase(Locale.ROOT) : "AUTO";
        if ("GRS_AI".equals(adapter)) return true;
        return provider != null && "grsai".equalsIgnoreCase(provider.getProviderId());
    }

    private boolean isGeminiImageAdapter(ModelProvider provider) {
        String adapter = provider != null && provider.getAdapterType() != null
                ? provider.getAdapterType().trim().toUpperCase() : "AUTO";
        if ("GEMINI_IMAGE".equals(adapter)) return true;
        if ("OPENAI_IMAGE".equals(adapter)) return false;
        String model = provider != null && provider.getModelName() != null
                ? provider.getModelName().toLowerCase() : "";
        return model.contains("gemini");
    }

    private boolean isGptImageModel(ModelProvider provider) {
        String model = provider != null && provider.getModelName() != null
                ? provider.getModelName().toLowerCase()
                : "";
        return model.contains("gpt-image") || model.contains("gpt image");
    }

    private RestTemplate templateFor(ModelProvider provider) {
        if (provider == null) return restTemplate;
        int timeout = Math.max(30, Math.min(ModelProviderDefaults.timeoutSeconds(provider), 3600));
        if (timeout == ModelProviderDefaults.CHAT_TIMEOUT_SECONDS) return restTemplate;
        return createRestTemplate(Math.min(30, timeout), timeout);
    }

    private RestTemplate createRestTemplate(int connectTimeoutSeconds, int readTimeoutSeconds) {
        SimpleClientHttpRequestFactory simpleFactory = new SimpleClientHttpRequestFactory();
        simpleFactory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        simpleFactory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));

        ClientHttpRequestFactory requestFactory = simpleFactory;
        RestTemplate template = new RestTemplate(requestFactory);
        template.getInterceptors().add(new LlmTrafficLoggingInterceptor(debugService));
        return template;
    }

    private void throwIfProviderError(Map<?, ?> responseBody) {
        LlmErrorMessageUtils.payloadError(responseBody)
                .ifPresent(error -> { throw new RuntimeException(error); });
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private byte[] extractImageBytes(ResponseEntity<Map> response) {
        if (response.getBody() == null) {
            throw new RuntimeException("Image API returned empty response");
        }
        throwIfProviderError(response.getBody());

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody().get("data");
        if (data == null || data.isEmpty()) {
            throw new RuntimeException("Image API returned no data");
        }

        String b64Json = (String) data.get(0).get("b64_json");
        if (b64Json != null && !b64Json.isBlank()) {
            return decodeBase64Image(b64Json);
        }

        String imageUrl = (String) data.get(0).get("url");
        if (imageUrl != null && !imageUrl.isBlank()) {
            log.debug("No b64_json, downloading from URL: {}", imageUrl);
            ResponseEntity<byte[]> imageResponse = restTemplate.getForEntity(imageUrl, byte[].class);
            byte[] body = imageResponse.getBody();
            if (body != null && body.length > 0) {
                return body;
            }
        }

        throw new RuntimeException("Image API returned no image data");
    }

    private static class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;
        private final String contentType;

        NamedByteArrayResource(byte[] byteArray, String filename, String contentType) {
            super(byteArray);
            this.filename = filename != null && !filename.isBlank() ? filename : "reference.png";
            this.contentType = contentType != null && !contentType.isBlank() ? contentType : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }

        @Override
        public String getFilename() {
            return filename;
        }

        public String getContentType() {
            return contentType;
        }
    }

    /**
     * Tests connectivity to a provider endpoint by calling GET {baseUrl}/models.
     * Returns a structured response with success, message, and response time.
     * Test failures are returned as success=false rather than thrown as exceptions.
     */
    public TestConnectionResponse testConnection(String baseUrl, String apiKey) {
        String url = ApiRequestUrlUtils.endpoint(baseUrl, "/models");

        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.setBearerAuth(apiKey);
            log.info("Testing connection to {} with API key (length={})", url, apiKey.length());
        } else {
            log.info("Testing connection to {} without API key", url);
        }

        HttpEntity<Void> request = new HttpEntity<>(headers);

        long start = System.currentTimeMillis();
        try {
            restTemplate.exchange(url, HttpMethod.GET, request, String.class);
            long elapsed = System.currentTimeMillis() - start;
            log.debug("Connection test to {} successful in {}ms", url, elapsed);
            return new TestConnectionResponse(true, "Connection successful", elapsed);
        } catch (RestClientException e) {
            long elapsed = System.currentTimeMillis() - start;
            String errorMsg = e.getMessage();
            // Include the HTTP status code in the error message if available
            if (e instanceof org.springframework.web.client.HttpStatusCodeException httpEx) {
                errorMsg = "HTTP " + httpEx.getStatusCode().value() + ": " + httpEx.getResponseBodyAsString();
                // Truncate long error responses
                if (errorMsg.length() > 500) {
                    errorMsg = errorMsg.substring(0, 500) + "...";
                }
            }
            log.warn("Connection test to {} failed in {}ms: {}", url, elapsed, errorMsg);
            return new TestConnectionResponse(false, errorMsg, elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("Connection test to {} failed in {}ms: {}", url, elapsed, e.getMessage());
            return new TestConnectionResponse(false, e.getMessage(), elapsed);
        }
    }

    /**
     * Grsai does not expose an OpenAI-compatible /models endpoint. Test the
     * documented task-result route without a task id instead. A structured
     * 400/404 validation response means routing and authentication succeeded;
     * 401/403 or an authentication-related payload means the key was rejected.
     */
    public TestConnectionResponse testGrsaiConnection(String baseUrl, String apiKey) {
        if (!hasText(apiKey)) {
            return new TestConnectionResponse(false, "请先填写 Grsai API Key", null);
        }
        String url = ApiRequestUrlUtils.rootEndpoint(baseUrl, "/v1/api/result");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<Void> request = new HttpEntity<>(headers);

        long start = System.currentTimeMillis();
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
            long elapsed = System.currentTimeMillis() - start;
            String body = response.getBody();
            if (isGrsaiAuthenticationFailure(body)) {
                return new TestConnectionResponse(false, "Grsai 拒绝了 API Key：" + compactBody(body), elapsed);
            }
            return new TestConnectionResponse(true, "Grsai 连接成功，API Key 已被服务端接受", elapsed);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            long elapsed = System.currentTimeMillis() - start;
            int status = e.getStatusCode().value();
            String body = e.getResponseBodyAsString();
            if (status == 401 || status == 403 || isGrsaiAuthenticationFailure(body)) {
                return new TestConnectionResponse(false,
                        "Grsai API Key 验证失败（HTTP " + status + "）：" + compactBody(body), elapsed);
            }
            if ((status == 400 || status == 404) && isGrsaiExpectedValidationResponse(body)) {
                return new TestConnectionResponse(true,
                        "Grsai 连接成功，API Key 已被服务端接受（测试请求未提供有效任务 ID）", elapsed);
            }
            return new TestConnectionResponse(false,
                    "Grsai 连接失败（HTTP " + status + "）：" + compactBody(body), elapsed);
        } catch (RestClientException e) {
            long elapsed = System.currentTimeMillis() - start;
            return new TestConnectionResponse(false, "Grsai 连接失败：" + e.getMessage(), elapsed);
        }
    }

    private boolean isGrsaiAuthenticationFailure(String body) {
        if (!hasText(body)) return false;
        String normalized = body.toLowerCase(Locale.ROOT);
        return normalized.contains("unauthorized") || normalized.contains("forbidden")
                || normalized.contains("invalid api key") || normalized.contains("invalid apikey")
                || normalized.contains("api key invalid") || normalized.contains("apikey invalid")
                || normalized.contains("invalid access token") || normalized.contains("access token invalid")
                || normalized.contains("api key 无效") || normalized.contains("apikey 无效")
                || normalized.contains("api key 错误") || normalized.contains("apikey 错误")
                || normalized.contains("未授权") || normalized.contains("无权限")
                || normalized.contains("鉴权失败") || normalized.contains("认证失败")
                || normalized.contains("密钥无效");
    }

    private boolean isGrsaiExpectedValidationResponse(String body) {
        if (!hasText(body)) return false;
        String trimmed = body.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return false;
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        return normalized.contains("id") || normalized.contains("task")
                || normalized.contains("record") || normalized.contains("任务")
                || normalized.contains("不存在") || normalized.contains("未找到")
                || normalized.contains("不能为空") || normalized.contains("缺少");
    }

    private String compactBody(String body) {
        if (!hasText(body)) return "服务端未返回详细信息";
        String compact = body.replaceAll("\\s+", " ").trim();
        return compact.length() <= 500 ? compact : compact.substring(0, 500) + "...";
    }

    /**
     * Fetches available model names from a provider endpoint by calling GET {baseUrl}/models.
     * Parses the OpenAI-compatible response format: { "data": [ { "id": "model-name", ... }, ... ] }
     */
    public FetchModelsResponse fetchModels(String baseUrl, String apiKey) {
        String url = ApiRequestUrlUtils.endpoint(baseUrl, "/models");

        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.setBearerAuth(apiKey);
        }

        HttpEntity<Void> request = new HttpEntity<>(headers);

        log.debug("Fetching models from {}", url);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, request, Map.class);

        FetchModelsResponse result = new FetchModelsResponse();

        if (response.getBody() == null) {
            result.setModels(Collections.emptyList());
            return result;
        }

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody().get("data");
        if (data == null || data.isEmpty()) {
            result.setModels(Collections.emptyList());
            return result;
        }

        List<String> models = data.stream()
                .map(m -> (String) m.get("id"))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        result.setModels(models);
        log.debug("Fetched {} models from {}", models.size(), url);
        return result;
    }

    /**
     * Test image generation by creating a small test image.
     * Used for IMAGE provider testing in the admin UI.
     */
    public byte[] testImageGeneration(ModelProvider provider) {
        String testPrompt = "A simple red circle on white background, minimalist";
        return generateImage(testPrompt, provider);
    }
}
