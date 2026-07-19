package com.gs.ais.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gs.ais.config.FeishuProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Minimal Feishu OpenAPI client used by the bot callback. */
@Component
public class FeishuApiClient {

    private final FeishuProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private volatile AccessToken cachedToken;

    @Autowired
    public FeishuApiClient(FeishuProperties properties, @Qualifier("feishuObjectMapper") ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    FeishuApiClient(FeishuProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public void replyText(String messageId, String text) {
        String safeText = text == null || text.isBlank() ? "暂无可返回的内容。" : text;
        if (safeText.length() > 4000) safeText = safeText.substring(0, 4000) + "\n\n（回复内容过长，已截断）";
        postJson("/im/v1/messages/" + encodePath(messageId) + "/reply", Map.of(
                "msg_type", "text",
                "content", writeJson(Map.of("text", safeText))));
    }

    public void replyImage(String messageId, byte[] image, String filename, String contentType) {
        String imageKey = uploadImage(image, filename, contentType);
        postJson("/im/v1/messages/" + encodePath(messageId) + "/reply", Map.of(
                "msg_type", "image",
                "content", writeJson(Map.of("image_key", imageKey))));
    }

    public DownloadedFile downloadMessageImage(String messageId, String imageKey) {
        String path = "/im/v1/messages/" + encodePath(messageId) + "/resources/" + encodePath(imageKey) + "?type=image";
        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(apiUri(path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + tenantAccessToken())
                .GET()
                .build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new FeishuApiException("下载飞书图片失败（HTTP " + response.statusCode() + "）："
                    + responseText(response.body()));
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("image/png");
        return new DownloadedFile(response.body(), contentType);
    }

    private String uploadImage(byte[] image, String filename, String contentType) {
        if (image == null || image.length == 0) throw new IllegalArgumentException("图片内容为空");
        String boundary = "----ais-" + UUID.randomUUID();
        byte[] body = multipartImageBody(boundary, image, filename, contentType);
        HttpResponse<String> response = send(HttpRequest.newBuilder(apiUri("/im/v1/images"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + tenantAccessToken())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode json = parseApiResponse(response, "上传飞书图片");
        String imageKey = json.path("data").path("image_key").asText();
        if (imageKey.isBlank()) throw new FeishuApiException("上传飞书图片失败：响应缺少 image_key");
        return imageKey;
    }

    private byte[] multipartImageBody(String boundary, byte[] image, String filename, String contentType) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writePart(out, boundary, "image_type", null, "text/plain; charset=UTF-8", "message".getBytes(StandardCharsets.UTF_8));
            writePart(out, boundary, "image", filename == null || filename.isBlank() ? "generated.png" : filename,
                    contentType == null || contentType.isBlank() ? "image/png" : contentType, image);
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException e) {
            throw new FeishuApiException("构造飞书图片上传请求失败", e);
        }
    }

    private void writePart(ByteArrayOutputStream out, String boundary, String name, String filename,
                           String contentType, byte[] content) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        String disposition = "Content-Disposition: form-data; name=\"" + name + "\"";
        if (filename != null) disposition += "; filename=\"" + filename.replace("\"", "") + "\"";
        out.write((disposition + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(content);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void postJson(String path, Map<String, Object> requestBody) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(apiUri(path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + tenantAccessToken())
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(requestBody), StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        parseApiResponse(response, "调用飞书消息接口");
    }

    private String tenantAccessToken() {
        AccessToken current = cachedToken;
        if (current != null && current.expiresAt().isAfter(Instant.now().plusSeconds(60))) return current.value();
        synchronized (this) {
            current = cachedToken;
            if (current != null && current.expiresAt().isAfter(Instant.now().plusSeconds(60))) return current.value();
            if (!properties.isConfigured()) throw new FeishuApiException("飞书机器人尚未配置 app-id / app-secret");
            HttpResponse<String> response = send(HttpRequest.newBuilder(apiUri("/auth/v3/tenant_access_token/internal"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(writeJson(Map.of(
                            "app_id", properties.getAppId(), "app_secret", properties.getAppSecret())), StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode json = parseApiResponse(response, "获取飞书 tenant_access_token");
            String token = json.path("tenant_access_token").asText();
            if (token.isBlank()) throw new FeishuApiException("获取飞书 tenant_access_token 失败：响应缺少 token");
            int expire = json.path("expire").asInt(7200);
            cachedToken = new AccessToken(token, Instant.now().plusSeconds(Math.max(expire, 120)));
            return token;
        }
    }

    private JsonNode parseApiResponse(HttpResponse<String> response, String action) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new FeishuApiException(action + "失败（HTTP " + response.statusCode() + "）：" + response.body());
        }
        try {
            JsonNode json = objectMapper.readTree(response.body());
            int code = json.path("code").asInt(0);
            if (code != 0) throw new FeishuApiException(action + "失败（code " + code + "）：" + json.path("msg").asText("未知错误"));
            return json;
        } catch (IOException e) {
            throw new FeishuApiException(action + "失败：响应不是有效 JSON", e);
        }
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return httpClient.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FeishuApiException("调用飞书接口时被中断", e);
        } catch (IOException e) {
            throw new FeishuApiException("调用飞书接口失败：" + e.getMessage(), e);
        }
    }

    private URI apiUri(String path) {
        String base = properties.getApiBaseUrl() == null || properties.getApiBaseUrl().isBlank()
                ? "https://open.feishu.cn/open-apis" : properties.getApiBaseUrl().replaceAll("/+$", "");
        return URI.create(base + path);
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (IOException e) { throw new FeishuApiException("序列化飞书请求失败", e); }
    }

    private String encodePath(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private String responseText(byte[] value) { return new String(value == null ? new byte[0] : value, StandardCharsets.UTF_8); }

    public record DownloadedFile(byte[] content, String contentType) { }
    private record AccessToken(String value, Instant expiresAt) { }

    public static class FeishuApiException extends RuntimeException {
        public FeishuApiException(String message) { super(message); }
        public FeishuApiException(String message, Throwable cause) { super(message, cause); }
    }
}
