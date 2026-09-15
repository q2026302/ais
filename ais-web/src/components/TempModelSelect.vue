<script setup lang="ts">
import { computed } from 'vue'
import type { ModelProvider } from '@/types'
import { providerLabel, usageHint } from '@/utils/modelDisplay'

/**
 * 对话框内的模型下拉框：专职「临时切换」。
 *
 * 选择只影响本次发送 / 本次生成，发送后由调用方重置为 null（跟随会话默认）。
 * 旁边的可见提示让用户一眼看出当前是临时状态还是默认状态。
 * PC / 移动 PWA / H5 三端共用。
 */
const props = defineProps<{
  providers: ModelProvider[]
  /** 临时选择的模型 id；null 表示跟随会话默认。 */
  modelValue: number | null
  /**
   * 该会话**实际生效**的默认模型 id（临时覆盖之外最终会被调用的那个模型，
   * 即 会话默认 → 用户默认 → 系统启用项 的解析结果）。
   */
  defaultProviderId?: number | null
  /** 解析不到 defaultProviderId 时的展示名兜底；不会覆盖已解析出的模型名。 */
  defaultLabel?: string
  disabled?: boolean
  /** 无障碍 / title 文案，例如「临时对话模型」。 */
  selectLabel?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: number | null]
}>()

/**
 * 「会话默认（X）」里的 X 必须是**该会话实际生效的默认模型名**，三端一致。
 *
 * 优先按 defaultProviderId（调用方传入的已解析生效 id：会话默认 → 用户默认 →
 * 系统启用项）在模型表里解析；解析不到（例如供应商已删除）才退回显式
 * defaultLabel / 「系统默认」。不再让一个可能过期的标签盖住真实生效的模型，
 * 否则会出现「显示 B、实际跑 A」。
 */
const resolvedDefaultLabel = computed(() => {
  const provider = props.providers.find((item) => item.id === props.defaultProviderId)
  if (provider) return providerLabel(provider)
  const provided = (props.defaultLabel || '').trim()
  return provided || '系统默认'
})

const temporaryLabel = computed(() => {
  if (props.modelValue == null) return ''
  const provider = props.providers.find((item) => item.id === props.modelValue)
  return provider ? providerLabel(provider) : ''
})

const hint = computed(() => usageHint({
  temporaryLabel: temporaryLabel.value,
  defaultLabel: resolvedDefaultLabel.value,
}))

function handleChange(value: number | null) {
  emit('update:modelValue', value ?? null)
}
</script>

<template>
  <div class="temp-model-select" :class="{ 'is-temporary': modelValue != null }">
    <el-select
      :model-value="modelValue"
      :disabled="disabled"
      size="small"
      class="temp-model-select__control"
      :aria-label="selectLabel || '临时切换模型'"
      @update:model-value="handleChange"
    >
      <el-option :label="`会话默认（${resolvedDefaultLabel}）`" :value="null" />
      <el-option
        v-for="provider in providers"
        :key="provider.id"
        :label="providerLabel(provider)"
        :value="provider.id"
      />
    </el-select>
    <span class="temp-model-select__hint" :title="hint">{{ hint }}</span>
  </div>
</template>

<style scoped>
.temp-model-select {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}
.temp-model-select__control {
  width: 168px;
  flex: 0 0 auto;
}
.temp-model-select__hint {
  min-width: 0;
  flex: 1 1 auto;
  overflow: hidden;
  color: #8a94aa;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.temp-model-select.is-temporary .temp-model-select__hint {
  color: #4e62d2;
  font-weight: 700;
}
@media (max-width: 900px) {
  .temp-model-select__control { width: 128px; }
  /* 窄屏（移动 PWA / H5 基本一直处于该宽度）不隐藏「本次使用」提示：
     允许截断/省略号，但必须看得见。 */
  .temp-model-select__hint { display: block; }
}
</style>
