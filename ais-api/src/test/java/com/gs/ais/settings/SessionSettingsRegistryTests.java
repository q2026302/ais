package com.gs.ais.settings;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参数注册表本身的语义：默认值补齐、分组内合并、显式 null 清空、未识别键忽略。
 * 这些都是“以后加参数不改表/不改接口”的基础契约，所以放在纯单元测试里锁死。
 */
class SessionSettingsRegistryTests {

    @Test
    void defaultsContainFirstBatchDrawParameters() {
        Map<String, Object> defaults = SessionSettingsRegistry.defaults();
        assertEquals(List.of("draw"), List.copyOf(defaults.keySet()));

        @SuppressWarnings("unchecked")
        Map<String, Object> draw = (Map<String, Object>) defaults.get("draw");
        assertEquals("1024x1024", draw.get("size"));
        assertEquals("auto", draw.get("quality"));
        assertEquals("png", draw.get("format"));
    }

    @Test
    void effectiveFallsBackToRegistryDefaultsWhenNothingStored() {
        assertEquals(SessionSettingsRegistry.defaults(), SessionSettingsRegistry.effective(null));
        assertEquals(SessionSettingsRegistry.defaults(), SessionSettingsRegistry.effective(""));
        // 脏数据 / 非法 JSON 也不能炸，按“没有任何设置”处理。
        assertEquals(SessionSettingsRegistry.defaults(), SessionSettingsRegistry.effective("{not json"));
        assertEquals(SessionSettingsRegistry.defaults(), SessionSettingsRegistry.effective("\"a string\""));
    }

    @Test
    void effectiveOverlaysStoredValuesOnTopOfDefaults() {
        Map<String, Object> effective = SessionSettingsRegistry.effective("{\"draw\":{\"quality\":\"high\"}}");
        @SuppressWarnings("unchecked")
        Map<String, Object> draw = (Map<String, Object>) effective.get("draw");
        assertEquals("high", draw.get("quality"));
        assertEquals("1024x1024", draw.get("size"));
        assertEquals("png", draw.get("format"));
    }

    @Test
    void mergeKeepsUntouchedKeysInsideGroup() {
        String stored = SessionSettingsRegistry.merge(null, Map.of("draw", Map.of("size", "1536x1024")));
        stored = SessionSettingsRegistry.merge(stored, Map.of("draw", Map.of("quality", "high")));

        // 稀疏存储：只有显式设置过且不等于默认值的键会被写下来。
        assertEquals("{\"draw\":{\"size\":\"1536x1024\",\"quality\":\"high\"}}", stored);

        @SuppressWarnings("unchecked")
        Map<String, Object> draw = (Map<String, Object>) SessionSettingsRegistry.effective(stored).get("draw");
        assertEquals("1536x1024", draw.get("size"));
        assertEquals("high", draw.get("quality"));
        assertEquals("png", draw.get("format"));
    }

    @Test
    void mergeIgnoresProviderIdKeysAndUnknownGroups() {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("chatProviderId", 12);
        patch.put("imageProviderId", 7);
        patch.put("unknownGroup", Map.of("whatever", "x"));

        assertFalse(SessionSettingsRegistry.containsGroupPatch(patch));
        assertNull(SessionSettingsRegistry.merge(null, patch));
    }

    @Test
    void mergeIgnoresUnknownKeysAndNonScalarValues() {
        Map<String, Object> drawPatch = new LinkedHashMap<>();
        drawPatch.put("nope", "x");
        drawPatch.put("size", List.of("a", "b"));
        drawPatch.put("quality", Map.of("nested", true));

        assertNull(SessionSettingsRegistry.merge(null, Map.of("draw", drawPatch)));

        // 已存储的值不会被非法输入抹掉。
        String stored = "{\"draw\":{\"quality\":\"high\"}}";
        assertEquals(stored, SessionSettingsRegistry.merge(stored, Map.of("draw", drawPatch)));
    }

    @Test
    void explicitNullResetsWholeGroupToDefaults() {
        String stored = "{\"draw\":{\"size\":\"1536x1024\",\"quality\":\"high\"}}";
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("draw", null);

        assertTrue(SessionSettingsRegistry.containsGroupPatch(patch));
        assertNull(SessionSettingsRegistry.merge(stored, patch));
        assertEquals(SessionSettingsRegistry.defaults(), SessionSettingsRegistry.effective(null));
    }

    @Test
    void explicitNullOnOneKeyOnlyResetsThatKey() {
        String stored = "{\"draw\":{\"size\":\"1536x1024\",\"quality\":\"high\"}}";
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("quality", null);
        String merged = SessionSettingsRegistry.merge(stored, Map.of("draw", inner));

        assertEquals("{\"draw\":{\"size\":\"1536x1024\"}}", merged);
        @SuppressWarnings("unchecked")
        Map<String, Object> draw = (Map<String, Object>) SessionSettingsRegistry.effective(merged).get("draw");
        assertEquals("1536x1024", draw.get("size"));
        assertEquals("auto", draw.get("quality"));
    }

    @Test
    void blankValueIsTreatedAsUnsetLikeGenerationOptions() {
        String stored = SessionSettingsRegistry.merge(null, Map.of("draw", Map.of("size", "   ")));
        assertNull(stored);

        // 与生成请求既有规则一致：去首尾空白后存储。
        String trimmed = SessionSettingsRegistry.merge(null, Map.of("draw", Map.of("size", " 1536x1024 ")));
        assertEquals("{\"draw\":{\"size\":\"1536x1024\"}}", trimmed);
    }

    @Test
    void valueEqualToDefaultIsNotPersisted() {
        assertNull(SessionSettingsRegistry.merge(null, Map.of("draw", Map.of("size", "1024x1024"))));
        assertNull(SessionSettingsRegistry.merge(null, Map.of("draw", Map.of("quality", "auto"))));
        assertNull(SessionSettingsRegistry.merge(null, Map.of("draw", Map.of("format", "png"))));
    }

    @Test
    void unknownStoredGroupsArePurgedOnMerge() {
        String stored = "{\"draw\":{\"quality\":\"high\"},\"legacy\":{\"x\":\"y\"}}";
        String merged = SessionSettingsRegistry.merge(stored, Map.of("draw", Map.of("size", "1536x1024")));
        assertEquals("{\"draw\":{\"quality\":\"high\",\"size\":\"1536x1024\"}}", merged);
    }

    @Test
    void scalarCoercionAcceptsNumbersAndBooleans() {
        String stored = SessionSettingsRegistry.merge(null, Map.of("draw", Map.of("size", 1536)));
        assertEquals("{\"draw\":{\"size\":\"1536\"}}", stored);
    }

    @Test
    void sanitizeNormalizesImportedJsonLikeTheNormalWritePath() {
        // 备份里可能含未知分组 / 未知键 / 非标量值 / 与默认值相同的值。
        String dirty = "{\"draw\":{\"size\":\"16:9\",\"quality\":{\"nested\":1},"
                + "\"format\":\"png\",\"nope\":\"x\"},\"legacy\":{\"a\":1}}";

        String cleaned = SessionSettingsRegistry.sanitize(dirty);

        assertEquals("{\"draw\":{\"size\":\"16:9\"}}", cleaned);
        // 与正常写入路径产出的稀疏 JSON 完全一致。
        assertEquals(
                SessionSettingsRegistry.merge(null, Map.of("draw", Map.of("size", "16:9"))),
                cleaned);

        // 非法 / 空白 / 非对象分组 / 只剩默认值 → 与正常路径一样「无设置」。
        assertNull(SessionSettingsRegistry.sanitize(null));
        assertNull(SessionSettingsRegistry.sanitize("   "));
        assertNull(SessionSettingsRegistry.sanitize("{not json"));
        assertNull(SessionSettingsRegistry.sanitize("{\"draw\":\"not-a-map\"}"));
        assertNull(SessionSettingsRegistry.sanitize("{\"draw\":{\"format\":\"png\"}}"));
        assertNull(SessionSettingsRegistry.sanitize("{\"legacy\":{\"x\":\"y\"}}"));
    }
}
