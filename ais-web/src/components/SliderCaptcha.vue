<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { CaptchaTrackPoint } from '@/api/auth'

const props = defineProps<{
  backgroundImage: string
  pieceImage: string
  backgroundWidth: number
  backgroundHeight: number
  pieceWidth: number
  pieceHeight: number
  pieceY: number
  disabled?: boolean
}>()

const emit = defineEmits<{
  verify: [payload: { x: number; track: CaptchaTrackPoint[] }]
}>()

const stageRef = ref<HTMLElement | null>(null)
const bgRef = ref<HTMLImageElement | null>(null)
const trackRef = ref<HTMLElement | null>(null)

const pieceLeft = ref(0)
const dragging = ref(false)
const scale = ref(1)

let track: CaptchaTrackPoint[] = []
let startTime = 0
let lastSampleTime = 0
let grabOffset = 0

const pieceStyle = computed(() => ({
  width: `${props.pieceWidth * scale.value}px`,
  height: `${props.pieceHeight * scale.value}px`,
  top: `${props.pieceY * scale.value}px`,
  left: `${pieceLeft.value}px`,
}))

const thumbStyle = computed(() => ({ left: `${pieceLeft.value}px` }))
const fillStyle = computed(() => ({ width: `${pieceLeft.value + 18}px` }))

function measureScale() {
  const bg = bgRef.value
  if (!bg || bg.clientWidth <= 0 || !props.backgroundWidth) return
  scale.value = bg.clientWidth / props.backgroundWidth
}

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value))
}

function onPointerDown(event: PointerEvent) {
  if (props.disabled || dragging.value) return
  measureScale()
  const trackRect = trackRef.value?.getBoundingClientRect()
  if (!trackRect) return
  dragging.value = true
  startTime = performance.now()
  lastSampleTime = startTime
  grabOffset = event.clientX - (trackRect.left + pieceLeft.value)
  track = []

  const stageRect = stageRef.value?.getBoundingClientRect()
  recordSample(event.clientX - trackRect.left - grabOffset, event.clientY, stageRect)

  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
  window.addEventListener('pointercancel', onPointerUp)
}

function onPointerMove(event: PointerEvent) {
  if (!dragging.value) return
  const trackRect = trackRef.value?.getBoundingClientRect()
  const stageRect = stageRef.value?.getBoundingClientRect()
  if (!trackRect || !stageRect) return
  const maxLeft = Math.max(0, stageRect.width - props.pieceWidth * scale.value)
  const next = clamp(event.clientX - trackRect.left - grabOffset, 0, maxLeft)
  pieceLeft.value = next
  const now = performance.now()
  if (now - lastSampleTime >= 8) {
    lastSampleTime = now
    recordSample(next, event.clientY, stageRect)
  }
}

function onPointerUp(event: PointerEvent) {
  if (!dragging.value) return
  dragging.value = false
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
  window.removeEventListener('pointercancel', onPointerUp)

  const stageRect = stageRef.value?.getBoundingClientRect()
  const finalX = Math.round(pieceLeft.value / scale.value)
  const lastSample = track.length > 0 ? track[track.length - 1] : undefined
  const finalY = stageRect
    ? Math.round((event.clientY - stageRect.top) / scale.value)
    : lastSample
      ? lastSample.y
      : 0

  const movedEnough =
    track.length >= 3 && Math.abs(pieceLeft.value) >= Math.max(4, 8 * scale.value)

  if (movedEnough) {
    track.push({ x: finalX, y: finalY, t: Math.round(performance.now() - startTime) })
    emit('verify', { x: finalX, track: [...track] })
  } else {
    reset()
  }
  track = []
}

function recordSample(pieceLeftCss: number, pointerY: number, stageRect: DOMRect | undefined) {
  if (!stageRect) return
  const x = Math.round(pieceLeftCss / scale.value)
  const y = Math.round((pointerY - stageRect.top) / scale.value)
  const t = Math.round(performance.now() - startTime)
  track.push({ x, y, t })
}

function reset() {
  pieceLeft.value = 0
  track = []
}

function onResize() {
  measureScale()
}

onMounted(() => {
  measureScale()
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
  window.removeEventListener('pointercancel', onPointerUp)
})

defineExpose({ reset })
</script>

<template>
  <div class="slider-captcha" :class="{ disabled }">
    <div ref="stageRef" class="slider-stage">
      <img
        ref="bgRef"
        :src="backgroundImage"
        class="slider-bg"
        alt="滑块验证码背景"
        draggable="false"
        @load="measureScale"
      />
      <img
        v-if="pieceImage"
        :src="pieceImage"
        class="slider-piece"
        :style="pieceStyle"
        alt=""
        draggable="false"
      />
    </div>
    <div
      ref="trackRef"
      class="slider-track"
      :class="{ dragging }"
      @pointerdown="onPointerDown"
    >
      <span class="track-fill" :style="fillStyle" />
      <span class="track-hint">按住滑块拖动，对准缺口后松手</span>
      <span class="track-thumb" :style="thumbStyle" aria-label="拖动滑块">
        <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
          <path d="M9 5l7 7-7 7" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </span>
    </div>
  </div>
</template>

<style scoped>
.slider-captcha {
  width: 100%;
  touch-action: none;
  user-select: none;
  -webkit-user-select: none;
}
.slider-captcha.disabled {
  opacity: 0.55;
  pointer-events: none;
}
.slider-stage {
  position: relative;
  width: 100%;
  overflow: hidden;
  border-radius: 12px;
  border: 1px solid #e0e5f5;
  background: #eef1fb;
  line-height: 0;
}
.slider-bg {
  display: block;
  width: 100%;
  height: auto;
  pointer-events: none;
}
.slider-piece {
  position: absolute;
  top: 0;
  left: 0;
  pointer-events: none;
  filter: drop-shadow(0 3px 6px rgba(24, 32, 78, 0.28));
  will-change: left;
}
.slider-track {
  position: relative;
  display: flex;
  align-items: center;
  height: 44px;
  margin-top: 10px;
  border-radius: 12px;
  background: #f2f4fc;
  border: 1px solid #e0e5f5;
  cursor: grab;
  overflow: hidden;
}
.slider-track.dragging {
  cursor: grabbing;
}
.track-fill {
  position: absolute;
  top: -1px;
  bottom: -1px;
  left: -1px;
  background: linear-gradient(110deg, rgba(83, 107, 245, 0.18), rgba(121, 91, 232, 0.22));
  border-right: 1px solid rgba(83, 107, 245, 0.4);
  pointer-events: none;
}
.track-hint {
  flex: 1;
  text-align: center;
  color: #9aa3ba;
  font-size: 12px;
  pointer-events: none;
}
.track-thumb {
  position: absolute;
  top: -1px;
  bottom: -1px;
  left: 0;
  display: grid;
  place-items: center;
  width: 44px;
  color: #fff;
  background: linear-gradient(135deg, #536bf5, #795be8);
  border-radius: 12px;
  box-shadow: 0 6px 16px rgba(82, 103, 246, 0.35);
  cursor: grab;
}
.slider-track.dragging .track-thumb {
  cursor: grabbing;
  box-shadow: 0 8px 20px rgba(82, 103, 246, 0.45);
}
</style>
