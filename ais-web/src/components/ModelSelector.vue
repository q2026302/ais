<script setup lang="ts">
import type { ModelProvider } from '@/types'

defineProps<{
  chatProviders: ModelProvider[]
  imageProviders: ModelProvider[]
  activeChatId: number | null
  activeImageId: number | null
}>()

const emit = defineEmits<{
  'update:activeChatId': [id: number | null]
  'update:activeImageId': [id: number | null]
}>()
</script>

<template>
  <div class="model-selector">
    <div class="model-field">
      <span class="label">对话:</span>
      <el-select
        :model-value="activeChatId"
        size="small"
        placeholder="默认"
        @update:model-value="emit('update:activeChatId', $event)"
      >
        <el-option label="全局默认" :value="null" />
        <el-option
          v-for="p in chatProviders"
          :key="p.id"
          :label="`${p.name || p.providerId} / ${p.modelName}`"
          :value="p.id"
        />
      </el-select>
    </div>
    <div class="model-field">
      <span class="label">图像:</span>
      <el-select
        :model-value="activeImageId"
        size="small"
        placeholder="默认"
        @update:model-value="emit('update:activeImageId', $event)"
      >
        <el-option label="全局默认" :value="null" />
        <el-option
          v-for="p in imageProviders"
          :key="p.id"
          :label="`${p.name || p.providerId} / ${p.modelName}`"
          :value="p.id"
        />
      </el-select>
    </div>
  </div>
</template>

<style scoped>
.model-selector { display: flex; align-items: center; gap: 7px; padding: 5px 8px; border: 1px solid #e7eaf6; border-radius: 12px; background: rgba(255,255,255,.78); box-shadow: 0 3px 10px rgba(47,60,116,.04); }
.model-field { display: flex; align-items: center; gap: 5px; }.label { color: #8b95ab; font-size: 11px; font-weight: 700; white-space: nowrap; }.model-field :deep(.el-select) { width: 145px; }.model-field :deep(.el-select__wrapper) { min-height: 27px; padding: 0 7px; border-radius: 7px; background: #f6f7fc; box-shadow: none; }.model-field :deep(.el-select__selected-item) { color: #59647d; font-size: 12px; }
@media (max-width: 1100px) { .model-field :deep(.el-select) { width: 110px; } }
@media (max-width: 750px) { .model-selector { gap: 3px; padding: 4px; }.label { display: none; }.model-field :deep(.el-select) { width: 92px; } }
</style>
