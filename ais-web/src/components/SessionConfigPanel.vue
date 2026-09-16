<script setup lang="ts">
import { computed } from 'vue'
import type { ModelProvider } from '@/types'
import { providerLabel } from '@/utils/modelDisplay'

/**
 * 「会话配置」内容：齿轮的统一含义（PC / 移动 PWA / H5 三端一致）。
 *
 * 包含会话默认对话模型、会话默认绘画模型、绘画参数。这里的选择会持久写入
 * 当前会话，之后的发送/生成默认使用；临时改选请使用对话框内的模型下拉框。
 * 三端各自用本地抽屉/弹层包裹本组件，避免三处各写一遍。
 *
 * 重要：模型 id 与绘画参数都绑定**会话存储值**（唯一来源），本组件不做任何
 * 「按选项集校正后」的工作副本；选项集只决定可选范围，超出范围的存储值会原样
 * 显示并给出提示，绝不静默改写。
 */
const props = defineProps<{
  chatProviders: ModelProvider[]
  imageProviders: ModelProvider[]
  /** 会话默认对话模型 id；null 表示跟随用户/系统默认。 */
  chatProviderId: number | null
  /** 会话默认绘画模型 id；null 表示跟随用户/系统默认。 */
  imageProviderId: number | null
  /** chatProviderId 为 null 时，用户/系统默认对话模型的展示名。 */
  chatDefaultLabel?: string
  /** imageProviderId 为 null 时，用户/系统默认绘画模型的展示名。 */
  imageDefaultLabel?: string
  /** 会话**存储的**绘画参数（唯一来源，不经校正）。 */
  drawSize: string
  drawQuality: string
  drawFormat: string
  drawSizeOptions: string[]
  drawQualityOptions: string[]
  drawFormatOptions: string[]
  /** 存储值超出当前生效模型选项集时的校验提示。 */
  drawOptionHints?: string[]
  disabled?: boolean
  /**
   * 不可用时的原因（例如「还没有会话，发送第一条消息后即可保存」）。
   * 非空时禁用全部控件并显示该说明，避免改动被静默丢弃。
   */
  disabledReason?: string
  /**
   * 该端是否有「生成面板」可临时调整绘画参数。移动 PWA / H5 没有，
   * 文案必须按端差异化，不能描述一个不存在的入口。
   */
  hasDrawPanel?: boolean
}>()

const emit = defineEmits<{
  'update:chatProviderId': [value: number | null]
  'update:imageProviderId': [value: number | null]
  'update:drawSize': [value: string]
  'update:drawQuality': [value: string]
  'update:drawFormat': [value: string]
}>()

const controlsDisabled = computed(() => props.disabled || Boolean(props.disabledReason))

const note = computed(() => {
  const base = '以下设置会保存到当前会话（持久生效），之后的发送与生成默认使用。'
  const chatTemp = '只想改这一次：对话用输入框旁的模型下拉框临时切换。'
  const drawTemp = props.hasDrawPanel === false
    ? ''
    : '绘画参数可在本次生成的绘画面板里临时调整。'
  return `${base}${chatTemp}${drawTemp}`
})
</script>

<template>
  <div class="session-config">
    <p class="session-config__note">{{ note }}</p>
    <p v-if="disabledReason" class="session-config__reason">{{ disabledReason }}</p>

    <section class="session-config__section">
      <h4 class="session-config__title">会话默认对话模型</h4>
      <el-select
        :model-value="chatProviderId"
        :disabled="controlsDisabled"
        class="session-config__control"
        popper-class="session-config-select-popper"
        aria-label="会话默认对话模型"
        @update:model-value="emit('update:chatProviderId', $event ?? null)"
      >
        <el-option :label="`跟随默认（${chatDefaultLabel || '系统默认'}）`" :value="null" />
        <el-option
          v-for="provider in chatProviders"
          :key="provider.id"
          :label="providerLabel(provider)"
          :value="provider.id"
        />
      </el-select>
    </section>

    <section class="session-config__section">
      <h4 class="session-config__title">会话默认绘画模型</h4>
      <el-select
        :model-value="imageProviderId"
        :disabled="controlsDisabled"
        class="session-config__control"
        popper-class="session-config-select-popper"
        aria-label="会话默认绘画模型"
        @update:model-value="emit('update:imageProviderId', $event ?? null)"
      >
        <el-option :label="`跟随默认（${imageDefaultLabel || '系统默认'}）`" :value="null" />
        <el-option
          v-for="provider in imageProviders"
          :key="provider.id"
          :label="providerLabel(provider)"
          :value="provider.id"
        />
      </el-select>
    </section>

    <section class="session-config__section">
      <h4 class="session-config__title">绘画参数</h4>
      <div class="session-config__grid">
        <label class="session-config__field">
          <span>尺寸 / 比例</span>
          <el-select
            :model-value="drawSize"
            :disabled="controlsDisabled"
            popper-class="session-config-select-popper"
            aria-label="绘画尺寸或比例"
            @update:model-value="emit('update:drawSize', $event)"
          >
            <el-option v-for="option in drawSizeOptions" :key="option" :label="option" :value="option" />
          </el-select>
        </label>
        <label class="session-config__field">
          <span>质量</span>
          <el-select
            :model-value="drawQuality"
            :disabled="controlsDisabled"
            popper-class="session-config-select-popper"
            aria-label="绘画质量"
            @update:model-value="emit('update:drawQuality', $event)"
          >
            <el-option
              v-for="option in drawQualityOptions"
              :key="option"
              :label="option.toUpperCase()"
              :value="option"
            />
          </el-select>
        </label>
        <label class="session-config__field">
          <span>格式</span>
          <el-select
            :model-value="drawFormat"
            :disabled="controlsDisabled"
            popper-class="session-config-select-popper"
            aria-label="图片格式"
            @update:model-value="emit('update:drawFormat', $event)"
          >
            <el-option
              v-for="option in drawFormatOptions"
              :key="option"
              :label="option.toUpperCase()"
              :value="option"
            />
          </el-select>
        </label>
      </div>
      <ul v-if="drawOptionHints && drawOptionHints.length" class="session-config__hints">
        <li v-for="hint in drawOptionHints" :key="hint">{{ hint }}</li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.session-config { display: grid; gap: 16px; }
.session-config__note {
  margin: 0;
  color: #8a94aa;
  font-size: 12px;
  line-height: 1.55;
}
.session-config__reason {
  margin: 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: #fff7e6;
  color: #a86a00;
  font-size: 12px;
  line-height: 1.5;
}
.session-config__section { display: grid; gap: 8px; }
.session-config__title {
  margin: 0;
  color: #4a5674;
  font-size: 13px;
  font-weight: 700;
}
.session-config__control { width: 100%; }
.session-config__grid {
  display: grid;
  gap: 10px;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
}
.session-config__field {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 5px;
}
.session-config__field > span { color: #7a8498; font-size: 12px; font-weight: 700; }
.session-config__hints {
  margin: 0;
  padding-left: 18px;
  color: #a86a00;
  font-size: 12px;
  line-height: 1.5;
}
</style>

<!--
  非 scoped：el-select 的候选列表被 teleport 到 body，脱离本组件作用域，
  必须用全局选择器覆盖。
-->
<style>
/*
  层级收口原因（PC 端被面板遮挡的根因）：PC 的 ChatInput 抽屉为了让抽屉压住
  页面内容，用 `z-index: 3001 !important` 手工把 el-drawer 抬到了 3001，遮罩
  则固定在 3000；但 Element Plus 的弹层 z-index 依赖内部自增计数器
  （`useZIndex`，基数 2000），而 `:z-index` 显式传值的抽屉**不会**推进该计数器，
  于是抽屉内 el-select 的候选列表只拿到 2000 出头，被 3001 的面板盖住 ——
  这正是「PC 展开下拉看不见候选、移动端正常」的差异所在（移动端抽屉未手工抬层，
  自增层级天然高于面板）。

  这里给本面板全部 5 个下拉的候选列表一个高于 PC 抽屉(3001)/遮罩(3000)、
  低于参考图预览浮层(4000) 的固定层级，三端共用；移动端抽屉自增层级在 2001
  左右，因此该值同样高于移动端面板，行为保持一致、不回归。
*/
.el-popper.session-config-select-popper {
  z-index: 3100 !important;
}
</style>
