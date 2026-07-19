<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  content: string
  maxLines?: number
}>(), {
  maxLines: 3,
})

const contentRef = ref<HTMLElement | null>(null)
const expanded = ref(false)
const canToggle = ref(false)
let resizeObserver: ResizeObserver | undefined

const isCollapsed = computed(() => canToggle.value && !expanded.value)

function updateOverflowState() {
  const element = contentRef.value
  if (!element) return

  const style = window.getComputedStyle(element)
  const lineHeight = Number.parseFloat(style.lineHeight)
  const fallbackLineHeight = Number.parseFloat(style.fontSize) * 1.2
  const resolvedLineHeight = Number.isFinite(lineHeight) ? lineHeight : fallbackLineHeight

  canToggle.value = element.scrollHeight > resolvedLineHeight * props.maxLines + 1
}

function scheduleOverflowCheck() {
  void nextTick(updateOverflowState)
}

function toggle() {
  expanded.value = !expanded.value
}

watch(() => props.content, () => {
  expanded.value = false
  scheduleOverflowCheck()
})

watch(() => props.maxLines, scheduleOverflowCheck)

onMounted(() => {
  scheduleOverflowCheck()
  if (typeof ResizeObserver !== 'undefined' && contentRef.value) {
    resizeObserver = new ResizeObserver(scheduleOverflowCheck)
    resizeObserver.observe(contentRef.value)
  }
})

onBeforeUnmount(() => resizeObserver?.disconnect())
</script>

<template>
  <div class="collapsible-message-text">
    <div
      ref="contentRef"
      class="message-text-content"
      :class="{ collapsed: isCollapsed }"
      :style="isCollapsed ? { WebkitLineClamp: maxLines } : undefined"
    >
      {{ content }}
    </div>
    <button
      v-if="canToggle"
      type="button"
      class="message-text-toggle"
      :aria-expanded="expanded"
      @click="toggle"
    >
      {{ expanded ? '收起' : '展开' }}
    </button>
  </div>
</template>

<style scoped>
.collapsible-message-text {
  min-width: 0;
}

.message-text-content {
  white-space: pre-wrap;
  word-break: break-word;
}

.message-text-content.collapsed {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
}

.message-text-toggle {
  display: inline-flex;
  margin-top: 5px;
  padding: 0;
  color: inherit;
  font: inherit;
  font-size: 12px;
  line-height: 1.4;
  cursor: pointer;
  border: 0;
  border-bottom: 1px solid currentColor;
  background: transparent;
  opacity: .82;
}

.message-text-toggle:hover {
  opacity: 1;
}
</style>
