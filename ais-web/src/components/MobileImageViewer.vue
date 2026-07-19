<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Close, ArrowLeft, ArrowRight } from '@element-plus/icons-vue'

const props = withDefaults(defineProps<{
  visible: boolean
  images: string[]
  initialIndex?: number
}>(), {
  initialIndex: 0,
})

const emit = defineEmits<{
  (event: 'update:visible', value: boolean): void
  (event: 'change', index: number): void
}>()

const currentIndex = ref(0)
const scale = ref(1)
const translateX = ref(0)
const translateY = ref(0)
const imageLoaded = ref(false)
const imageRef = ref<HTMLImageElement | null>(null)
const stageRef = ref<HTMLElement | null>(null)

let startX = 0
let startY = 0
let startTime = 0
let startScale = 1
let startTranslateX = 0
let startTranslateY = 0
let startDistance = 0
let gestureMoved = false
let pinchActive = false
let lastTapAt = 0
let lastTapX = 0
let lastTapY = 0
let previousBodyOverflow = ''

const currentImage = computed(() => props.images[currentIndex.value] || '')
const counterLabel = computed(() => props.images.length > 1 ? `${currentIndex.value + 1} / ${props.images.length}` : '')
const imageStyle = computed(() => ({
  transform: `translate3d(${translateX.value}px, ${translateY.value}px, 0) scale(${scale.value})`,
}))

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value))
}
function distance(a: Touch, b: Touch) {
  return Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY)
}
function resetTransform() {
  scale.value = 1
  translateX.value = 0
  translateY.value = 0
}
function resetForIndex(index: number) {
  currentIndex.value = clamp(index, 0, Math.max(0, props.images.length - 1))
  imageLoaded.value = false
  resetTransform()
  emit('change', currentIndex.value)
}
function close() {
  emit('update:visible', false)
}
function goTo(index: number) {
  if (props.images.length < 2) return
  const nextIndex = (index + props.images.length) % props.images.length
  resetForIndex(nextIndex)
}
function toggleZoom(x = 0, y = 0) {
  if (scale.value > 1.05) {
    resetTransform()
    return
  }
  scale.value = 2.5
  translateX.value = x * -1.2
  translateY.value = y * -1.2
  clampTranslation()
}
function clampTranslation() {
  const image = imageRef.value
  const stage = stageRef.value
  if (!image || !stage || scale.value <= 1) {
    if (scale.value <= 1) {
      translateX.value = 0
      translateY.value = 0
    }
    return
  }
  const imageRect = image.getBoundingClientRect()
  const stageRect = stage.getBoundingClientRect()
  const horizontalLimit = Math.max(0, (imageRect.width * (scale.value - 1)) / (2 * scale.value) + stageRect.width * 0.22)
  const verticalLimit = Math.max(0, (imageRect.height * (scale.value - 1)) / (2 * scale.value) + stageRect.height * 0.22)
  translateX.value = clamp(translateX.value, -horizontalLimit, horizontalLimit)
  translateY.value = clamp(translateY.value, -verticalLimit, verticalLimit)
}
function handleTouchStart(event: TouchEvent) {
  if (!event.touches.length) return
  if (event.touches.length >= 2) {
    event.preventDefault()
    pinchActive = true
    gestureMoved = true
    startDistance = distance(event.touches[0]!, event.touches[1]!)
    startScale = scale.value
    startTranslateX = translateX.value
    startTranslateY = translateY.value
    return
  }
  const touch = event.touches[0]!
  startX = touch.clientX
  startY = touch.clientY
  startTime = Date.now()
  startScale = scale.value
  startTranslateX = translateX.value
  startTranslateY = translateY.value
  gestureMoved = false
}
function handleTouchMove(event: TouchEvent) {
  if (!event.touches.length) return
  if (event.touches.length >= 2 || pinchActive) {
    if (event.touches.length < 2 || !startDistance) return
    event.preventDefault()
    const nextScale = clamp(startScale * (distance(event.touches[0]!, event.touches[1]!) / startDistance), 1, 4)
    scale.value = nextScale
    gestureMoved = true
    clampTranslation()
    return
  }
  const touch = event.touches[0]!
  const dx = touch.clientX - startX
  const dy = touch.clientY - startY
  if (Math.abs(dx) > 8 || Math.abs(dy) > 8) gestureMoved = true
  if (scale.value > 1.01) {
    event.preventDefault()
    translateX.value = startTranslateX + dx
    translateY.value = startTranslateY + dy
    clampTranslation()
  }
}
function handleTouchEnd(event: TouchEvent) {
  if (event.touches.length > 0) return
  if (pinchActive) {
    pinchActive = false
    startDistance = 0
    if (scale.value < 1.05) resetTransform()
    return
  }
  const changed = event.changedTouches[0]
  if (!changed) return
  const dx = changed.clientX - startX
  const dy = changed.clientY - startY
  const duration = Date.now() - startTime
  const absX = Math.abs(dx)
  const absY = Math.abs(dy)

  if (scale.value <= 1.01 && absY > 78 && absY > absX && dy > 0) {
    close()
    return
  }
  if (scale.value <= 1.01 && absX > 58 && absX > absY && duration < 700 && props.images.length > 1) {
    goTo(dx < 0 ? currentIndex.value + 1 : currentIndex.value - 1)
    return
  }
  if (!gestureMoved && duration < 320) {
    const now = Date.now()
    const tapDistance = Math.hypot(changed.clientX - lastTapX, changed.clientY - lastTapY)
    if (now - lastTapAt < 300 && tapDistance < 28) {
      toggleZoom(changed.clientX - window.innerWidth / 2, changed.clientY - window.innerHeight / 2)
      lastTapAt = 0
    } else {
      lastTapAt = now
      lastTapX = changed.clientX
      lastTapY = changed.clientY
    }
  }
}
function handleImageLoad() {
  imageLoaded.value = true
}
function handleKeydown(event: KeyboardEvent) {
  if (!props.visible) return
  if (event.key === 'Escape') close()
  else if (event.key === 'ArrowLeft') goTo(currentIndex.value - 1)
  else if (event.key === 'ArrowRight') goTo(currentIndex.value + 1)
}
function lockBody() {
  previousBodyOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
}
function unlockBody() {
  document.body.style.overflow = previousBodyOverflow
}

watch(() => props.visible, async (visible) => {
  if (visible) {
    resetForIndex(props.initialIndex)
    lockBody()
    await nextTick()
    imageRef.value?.focus()
  } else unlockBody()
})
watch(() => props.initialIndex, (index) => {
  if (props.visible) resetForIndex(index)
})
watch(() => props.images, (images) => {
  if (props.visible && currentIndex.value >= images.length) resetForIndex(Math.max(0, images.length - 1))
})
onMounted(() => window.addEventListener('keydown', handleKeydown))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleKeydown)
  unlockBody()
})
</script>

<template>
  <Teleport to="body">
    <Transition name="mobile-viewer-fade">
      <div v-if="visible" class="mobile-image-viewer" role="dialog" aria-modal="true" aria-label="图片查看器">
        <div class="mobile-viewer-backdrop" @click="close"></div>
        <header class="mobile-viewer-header">
          <button type="button" class="mobile-viewer-close" aria-label="关闭图片查看器" @click="close"><Close /></button>
          <span v-if="counterLabel" class="mobile-viewer-counter">{{ counterLabel }}</span>
          <span v-else class="mobile-viewer-tip">双指缩放 · 下拉关闭</span>
        </header>
        <section
          ref="stageRef"
          class="mobile-viewer-stage"
          @touchstart="handleTouchStart"
          @touchmove="handleTouchMove"
          @touchend="handleTouchEnd"
          @touchcancel="handleTouchEnd"
          @click.self="close"
        >
          <img
            v-if="currentImage"
            ref="imageRef"
            class="mobile-viewer-image"
            :class="{ loaded: imageLoaded }"
            :src="currentImage"
            :style="imageStyle"
            alt="预览图片"
            tabindex="-1"
            draggable="false"
            @load="handleImageLoad"
            @dblclick.stop="toggleZoom()"
          >
        </section>
        <footer v-if="props.images.length > 1" class="mobile-viewer-footer">
          <button type="button" aria-label="上一张" @click="goTo(currentIndex - 1)"><ArrowLeft /></button>
          <span>左右滑动切换图片</span>
          <button type="button" aria-label="下一张" @click="goTo(currentIndex + 1)"><ArrowRight /></button>
        </footer>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.mobile-image-viewer { position: fixed; inset: 0; z-index: 3000; color: #fff; background: #080b14; touch-action: none; }
.mobile-viewer-backdrop { position: absolute; inset: 0; background: rgba(8, 11, 20, .96); }
.mobile-viewer-header { position: absolute; z-index: 2; top: 0; right: 0; left: 0; display: flex; min-height: calc(56px + env(safe-area-inset-top)); align-items: center; justify-content: space-between; padding: env(safe-area-inset-top) 14px 0; pointer-events: none; }
.mobile-viewer-close, .mobile-viewer-footer button { display: grid; width: 40px; height: 40px; place-items: center; padding: 0; color: #fff; cursor: pointer; border: 0; border-radius: 50%; background: rgba(255, 255, 255, .14); pointer-events: auto; }
.mobile-viewer-close :deep(svg), .mobile-viewer-footer button :deep(svg) { width: 19px; }
.mobile-viewer-counter, .mobile-viewer-tip { padding: 6px 10px; color: rgba(255, 255, 255, .82); font-size: 12px; border-radius: 99px; background: rgba(255, 255, 255, .12); }
.mobile-viewer-stage { position: absolute; inset: 0; display: grid; place-items: center; overflow: hidden; touch-action: none; }
.mobile-viewer-image { display: block; max-width: 100%; max-height: 100%; object-fit: contain; user-select: none; opacity: 0; transition: opacity .16s ease; will-change: transform; }
.mobile-viewer-image.loaded { opacity: 1; }
.mobile-viewer-footer { position: absolute; z-index: 2; right: 0; bottom: calc(12px + env(safe-area-inset-bottom)); left: 0; display: flex; align-items: center; justify-content: center; gap: 18px; pointer-events: none; }
.mobile-viewer-footer span { padding: 7px 11px; color: rgba(255, 255, 255, .72); font-size: 11px; border-radius: 99px; background: rgba(255, 255, 255, .1); }
.mobile-viewer-footer button { width: 34px; height: 34px; pointer-events: auto; }
.mobile-viewer-fade-enter-active, .mobile-viewer-fade-leave-active { transition: opacity .18s ease; }
.mobile-viewer-fade-enter-from, .mobile-viewer-fade-leave-to { opacity: 0; }
</style>
