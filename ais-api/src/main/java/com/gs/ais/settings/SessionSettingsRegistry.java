package com.gs.ais.settings;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话设置（{@code sessions.settings} 文本列里的 JSON）的**唯一真源**。
 *
 * <p>这里是一张显式白名单：<b>分组 → 参数 key → {类型, 默认值, 校验规则}</b>。
 * 首批为 {@code draw} 组：
 * <pre>{"size": "1024x1024", "quality": "auto", "format": "png"}</pre>
 *
 * <h2>为什么以后加参数/加分组不用改表、不用改接口</h2>
 * <ul>
 *   <li>表：{@code sessions.settings} 是一个通用可空 TEXT 列，存的就是这段 JSON，
 *       不按参数拆列；新增参数只是 JSON 里多一个键。</li>
 *   <li>接口：{@code PATCH /api/sessions/{id}/settings} 的请求体是自由形状的
 *       {@code Map<String,Object>}，服务端只按本注册表逐键合并；新增分组/参数
 *       不需要新增 DTO 或端点。</li>
 *   <li>响应：生效值 = 注册表默认值 ∪ 已存储的显式值，因此新参数会自动出现在
 *       会话读接口的 {@code settings} 里。</li>
 * </ul>
 *
 * <h2>存储形态</h2>
 * 只持久化**显式设置过且与默认值不同**的键（稀疏 JSON），读取时补齐默认值。
 * “会话未设置 → 回退默认值”与“显式 null → 清空回默认值”因此是同一套覆盖逻辑。
 *
 * <p>未识别的分组 / 键 / 非标量值一律**忽略且不报错**，这样前端升级早于后端
 * （或反过来，后端先支持新键）都不会让请求失败。
 */
public final class SessionSettingsRegistry {

    /** 绘画参数分组。 */
    public static final String GROUP_DRAW = "draw";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * 参数类型 + 校验/归一化规则。
     *
     * <p>规则刻意与生成请求既有规则保持一致（{@code ImageGenerationService.cleanOption} /
     * {@code ImageGenerationQueueService.cleanOption}）：去首尾空白，空白视为“未设置”，
     * 不额外另立长度/枚举校验。
     */
    public enum SettingType {
        STRING {
            @Override
            public String normalize(String raw) {
                return raw == null || raw.isBlank() ? null : raw.trim();
            }
        };

        /** @return 归一化后的值；{@code null} 表示“未设置 / 回到默认值”。 */
        public abstract String normalize(String raw);
    }

    /** 一个已注册参数：key、类型、默认值。 */
    public record Field(String key, SettingType type, String defaultValue) {}

    private static final Map<String, List<Field>> GROUPS;
    private static final Map<String, Map<String, Field>> FIELDS;

    static {
        Map<String, List<Field>> groups = new LinkedHashMap<>();
        groups.put(GROUP_DRAW, List.of(
                new Field("size", SettingType.STRING, "1024x1024"),
                new Field("quality", SettingType.STRING, "auto"),
                new Field("format", SettingType.STRING, "png")));
        // 以后新增绘画参数、或新增其它用途的分组（例如 chat 组的某一项），
        // 只需要在这个 map 里追加，表结构与接口都不动。
        GROUPS = Collections.unmodifiableMap(groups);

        Map<String, Map<String, Field>> fields = new LinkedHashMap<>();
        for (Map.Entry<String, List<Field>> entry : GROUPS.entrySet()) {
            Map<String, Field> byKey = new LinkedHashMap<>();
            for (Field field : entry.getValue()) {
                byKey.put(field.key(), field);
            }
            fields.put(entry.getKey(), Collections.unmodifiableMap(byKey));
        }
        FIELDS = Collections.unmodifiableMap(fields);
    }

    private SessionSettingsRegistry() {
    }

    /** 已注册的分组名（例如 {@code draw}）。 */
    public static Set<String> groupNames() {
        return GROUPS.keySet();
    }

    /** 注册表默认值的深拷贝，例如 {@code {"draw":{"size":"1024x1024","quality":"auto","format":"png"}}}。 */
    public static Map<String, Object> defaults() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<Field>> entry : GROUPS.entrySet()) {
            Map<String, Object> group = new LinkedHashMap<>();
            for (Field field : entry.getValue()) {
                group.put(field.key(), field.defaultValue());
            }
            result.put(entry.getKey(), group);
        }
        return result;
    }

    /**
     * 生效设置：注册表默认值叠加已存储的显式值，未识别的键被丢弃。
     * 这是会话读接口回显的形态，也是前端唯一需要消费的形态。
     */
    public static Map<String, Object> effective(String storedJson) {
        Map<String, Object> result = defaults();
        for (Map.Entry<String, Object> groupEntry : parse(storedJson).entrySet()) {
            Map<String, Field> registered = FIELDS.get(groupEntry.getKey());
            if (registered == null || !(groupEntry.getValue() instanceof Map<?, ?> storedGroup)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> target = (Map<String, Object>) result.get(groupEntry.getKey());
            for (Map.Entry<?, ?> fieldEntry : storedGroup.entrySet()) {
                Field field = fieldEntry.getKey() == null ? null : registered.get(String.valueOf(fieldEntry.getKey()));
                if (field == null) continue;
                String normalized = normalize(fieldEntry.getValue(), field.type());
                if (normalized != null) target.put(field.key(), normalized);
            }
        }
        return result;
    }

    /** 补丁里是否出现了任何已注册分组的键（用于判断要不要写 settings 列）。 */
    public static boolean containsGroupPatch(Map<String, Object> patch) {
        if (patch == null || patch.isEmpty()) return false;
        for (String group : GROUPS.keySet()) {
            if (patch.containsKey(group)) return true;
        }
        return false;
    }

    /**
     * 把补丁合并进已存储的稀疏 JSON，返回新的稀疏 JSON（全空时返回 {@code null}）。
     *
     * <p>语义：
     * <ul>
     *   <li>分组内**合并**：只覆盖传入的 key，未出现的 key 保持原样；</li>
     *   <li>分组值显式 {@code null} → 清空该分组、回默认值；</li>
     *   <li>分组内某个 key 为 {@code null}（或归一化后为空）→ 清空该键、回默认值；</li>
     *   <li>未注册的分组 / key、以及非标量值 → 忽略且不报错。</li>
     * </ul>
     */
    public static String merge(String storedJson, Map<String, Object> patch) {
        Map<String, Object> stored = parse(storedJson);
        // 未注册的分组（历史遗留 / 前端误传）不参与存储，避免脏数据被反复写回。
        stored.keySet().retainAll(GROUPS.keySet());
        if (patch != null) {
            for (Map.Entry<String, Object> entry : patch.entrySet()) {
                String group = entry.getKey();
                if (group == null || !GROUPS.containsKey(group)) continue;

                Object raw = entry.getValue();
                if (raw == null) {
                    // 显式 null：整组清空，读取时回到注册表默认值。
                    stored.remove(group);
                    continue;
                }
                if (!(raw instanceof Map<?, ?> rawGroup)) continue;

                Map<String, Object> groupStore = mutableGroup(stored, group);
                for (Map.Entry<?, ?> fieldEntry : rawGroup.entrySet()) {
                    if (fieldEntry.getKey() == null) continue;
                    Field field = FIELDS.get(group).get(String.valueOf(fieldEntry.getKey()));
                    if (field == null) continue;

                    Object rawValue = fieldEntry.getValue();
                    // 非标量（对象 / 数组）视为未识别：忽略，不动已存储的值。
                    if (!isScalar(rawValue)) continue;

                    String normalized = normalize(rawValue, field.type());
                    if (normalized == null || normalized.equals(field.defaultValue())) {
                        // 显式 null / 空白 / 与默认值相同 → 该键不存在即为默认值。
                        groupStore.remove(field.key());
                    } else {
                        groupStore.put(field.key(), normalized);
                    }
                }
                if (groupStore.isEmpty()) stored.remove(group);
            }
        }
        return stored.isEmpty() ? null : JSON.writeValueAsString(stored);
    }

    /**
     * 把任意来源（例如备份导入、历史遗留数据）的 settings JSON 清洗成与
     * {@link #merge} 正常写入路径**完全一致**的稀疏 JSON：
     * 未注册的分组 / 键、非标量值、以及与默认值相同的值一律丢弃。
     *
     * <p>导入时必须走这里，否则备份里的未知键或非标量值会直接落库，导致
     * {@code sessions.settings} 的形状与正常写入路径不一致。
     *
     * @return 清洗后的稀疏 JSON；无任何有效设置时返回 {@code null}（与正常路径一致）。
     */
    public static String sanitize(String storedJson) {
        Map<String, Object> patch = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : parse(storedJson).entrySet()) {
            if (entry.getKey() != null && GROUPS.containsKey(entry.getKey())) {
                patch.put(entry.getKey(), entry.getValue());
            }
        }
        return merge(null, patch);
    }

    /** 容错解析：空白 / 非法 / 非对象内容一律当作“没有任何设置”。 */
    public static Map<String, Object> parse(String storedJson) {
        if (storedJson == null || storedJson.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Object> parsed = JSON.readValue(storedJson, new TypeReference<Map<String, Object>>() {});
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutableGroup(Map<String, Object> stored, String group) {
        Object existing = stored.get(group);
        if (existing instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        stored.put(group, created);
        return created;
    }

    /** 只接受标量（字符串 / 数字 / 布尔）；其余类型视为未识别，忽略。 */
    private static boolean isScalar(Object value) {
        return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    /** @return 归一化后的标量文本；非标量返回 {@code null}。 */
    private static String normalize(Object value, SettingType type) {
        if (value == null) return null;
        if (value instanceof String text) return type.normalize(text);
        if (value instanceof Number || value instanceof Boolean) return type.normalize(String.valueOf(value));
        return null;
    }
}
