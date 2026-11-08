<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps<{ modelValue: string; examples: string[]; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; submit: [] }>()
const input = ref<HTMLTextAreaElement>()
const canvas = ref<HTMLCanvasElement>()
const exampleIndex = ref(0)
const focused = ref(false)
const vanishing = ref(false)
const motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
let interval: ReturnType<typeof setInterval> | undefined
let frame = 0
let finishAnimation: (() => void) | undefined
const composing = ref(false)

function cancel() {
  cancelAnimationFrame(frame)
  vanishing.value = false
  finishAnimation?.()
  finishAnimation = undefined
}

function onKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || (!event.ctrlKey && !event.metaKey)) return
  if (event.isComposing || composing.value || props.disabled) return
  event.preventDefault()
  emit('submit')
}

function vanish(): Promise<void> {
  cancel()
  const textarea = input.value
  const surface = canvas.value
  if (!textarea || !surface || motionQuery.matches || document.hidden) return Promise.resolve()
  // ponytail: 仅采样当前可见文本区域，800 字上限内无需全页面粒子引擎。
  try {
    const style = getComputedStyle(textarea)
    const width = textarea.clientWidth
    const height = textarea.clientHeight
    const mask = document.createElement('canvas')
    mask.width = width
    mask.height = height
    const painter = mask.getContext('2d', { willReadFrequently: true })
    const context = surface.getContext('2d')
    if (!painter || !context || !width || !height) return Promise.resolve()
    painter.font = `${style.fontWeight} ${style.fontSize} ${style.fontFamily}`
    painter.fillStyle = '#fff'
    painter.textBaseline = 'top'
    const padding = parseFloat(style.paddingLeft)
    const lineHeight = parseFloat(style.lineHeight)
    let x = padding
    let y =
      parseFloat(style.paddingTop) -
      textarea.scrollTop +
      (lineHeight - parseFloat(style.fontSize)) / 2
    for (const character of textarea.value) {
      const charWidth = painter.measureText(character).width
      if (character === '\n' || x + charWidth > width - padding) {
        x = padding
        y += lineHeight
        if (character === '\n') continue
      }
      painter.fillText(character, x, y)
      x += charWidth
    }
    const pixels = painter.getImageData(0, 0, width, height).data
    const particles: { x: number; y: number; dx: number; dy: number }[] = []
    for (let py = 0; py < height; py += 3) {
      for (let px = 0; px < width; px += 3) {
        if ((pixels[(py * width + px) * 4 + 3] ?? 0) > 80) {
          particles.push({
            x: px,
            y: py,
            dx: 18 + Math.random() * 72,
            dy: (Math.random() - 0.6) * 80,
          })
        }
      }
    }
    const ratio = Math.min(window.devicePixelRatio || 1, 2)
    surface.width = width * ratio
    surface.height = height * ratio
    context.scale(ratio, ratio)
    context.fillStyle = style.color
    vanishing.value = true
    return new Promise((resolve) => {
      finishAnimation = resolve
      const start = performance.now()
      const render = (now: number) => {
        const progress = Math.min((now - start) / 760, 1)
        context.clearRect(0, 0, width, height)
        for (const particle of particles) {
          const phase = Math.max(0, Math.min(1, progress * 1.45 - (particle.x / width) * 0.45))
          context.globalAlpha = 1 - phase
          context.fillRect(
            particle.x + particle.dx * phase,
            particle.y + particle.dy * phase,
            2 * (1 - phase),
            2 * (1 - phase),
          )
        }
        if (progress < 1) frame = requestAnimationFrame(render)
        else cancel()
      }
      render(start)
    })
  } catch {
    // 绘制不可用时直接提交，动画永远不决定业务结果。
    cancel()
    return Promise.resolve()
  }
}

function syncMotion() {
  if (motionQuery.matches || document.hidden) cancel()
}

onMounted(() => {
  interval = setInterval(() => {
    if (
      !props.modelValue &&
      !focused.value &&
      !props.disabled &&
      !document.hidden &&
      !motionQuery.matches &&
      props.examples.length
    ) {
      exampleIndex.value = (exampleIndex.value + 1) % props.examples.length
    }
  }, 4200)
  motionQuery.addEventListener('change', syncMotion)
  document.addEventListener('visibilitychange', syncMotion)
})
onBeforeUnmount(() => {
  clearInterval(interval)
  cancel()
  motionQuery.removeEventListener('change', syncMotion)
  document.removeEventListener('visibilitychange', syncMotion)
})
defineExpose({ focus: () => input.value?.focus(), vanish, cancel })
</script>

<template>
  <div class="prompt-input" :class="{ vanishing }">
    <textarea
      ref="input"
      :value="modelValue"
      :readonly="disabled"
      aria-label="描述你想创建的应用"
      :aria-busy="disabled"
      maxlength="800"
      rows="3"
      @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
      @keydown="onKeydown"
      @compositionstart="composing = true"
      @compositionend="composing = false"
      @focus="focused = true"
      @blur="focused = false"
    />
    <div v-if="!modelValue" class="prompt-placeholder" aria-hidden="true">
      <Transition name="example" mode="out-in">
        <span :key="exampleIndex">{{ examples[exampleIndex] || '描述你想创建的应用…' }}</span>
      </Transition>
    </div>
    <canvas ref="canvas" class="prompt-particles" aria-hidden="true" />
  </div>
</template>

<style scoped>
.prompt-input {
  position: relative;
  min-width: 0;
}
.prompt-input textarea {
  display: block;
  width: 100%;
  height: 144px;
  resize: none;
  padding: 24px;
  border: 0;
  border-radius: 16px;
  background: transparent;
  color: #f0f5ff;
  caret-color: #b5d3ff;
  font: inherit;
  font-size: 16px;
  line-height: 1.8;
  outline: none;
  scrollbar-color: #53647d transparent;
}
.prompt-input textarea::selection {
  color: #fff;
  background: #426da6;
}
.prompt-placeholder {
  position: absolute;
  inset: 24px;
  pointer-events: none;
  color: #b2bed2;
  font-size: 16px;
  line-height: 1.8;
}
.prompt-placeholder span {
  display: block;
  max-width: 92%;
}
.prompt-particles {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  visibility: hidden;
}
.vanishing textarea {
  color: transparent;
  caret-color: transparent;
}
.vanishing .prompt-particles {
  visibility: visible;
}
.example-enter-active,
.example-leave-active {
  transition:
    opacity 0.25s ease,
    transform 0.25s ease;
}
.example-enter-from {
  opacity: 0;
  transform: translateY(8px);
}
.example-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
@media (max-width: 640px) {
  .prompt-input textarea {
    height: 150px;
    padding: 20px;
  }
  .prompt-placeholder {
    inset: 20px;
    font-size: 15px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .example-enter-active,
  .example-leave-active {
    transition: none;
  }
}
</style>
