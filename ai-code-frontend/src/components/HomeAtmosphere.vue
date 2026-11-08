<script setup lang="ts">
// 流线只装饰创作舞台，SVG 与 CSS 保持零运行时动画依赖。
const paths = Array.from({ length: 7 }, (_, i) => {
  const offset = i * 26
  return `M ${-120 + offset} -80 C ${80 + offset} 80, ${80 + offset} 380, 580 375 S ${1030 - offset} 170, ${1540 - offset} 680`
})
</script>

<template>
  <div class="home-atmosphere" aria-hidden="true">
    <svg
      class="background-lines"
      viewBox="0 0 1440 720"
      fill="none"
      preserveAspectRatio="xMidYMid slice"
    >
      <g v-for="(path, i) in paths" :key="path">
        <path :d="path" class="line-track" />
        <path
          :d="path"
          pathLength="1"
          class="line-light"
          :style="{ animationDelay: `${i * -2.7}s`, animationDuration: `${18 + i * 2}s` }"
        />
      </g>
      <g transform="translate(1440 0) scale(-1 1)">
        <path
          v-for="(path, i) in paths.slice(0, 4)"
          :key="path"
          :d="path"
          pathLength="1"
          class="line-light line-secondary"
          :style="{ animationDelay: `${i * -4 - 6}s` }"
        />
      </g>
    </svg>
  </div>
</template>

<style scoped>
.home-atmosphere {
  position: absolute;
  inset: 0;
  pointer-events: none;
  overflow: hidden;
  background: var(--ui-shell);
}
.background-lines {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0.04;
  mask-image: linear-gradient(var(--ui-ink), var(--ui-ink) 55%, transparent);
}
.line-track {
  stroke: var(--ui-accent);
  stroke-width: 0.6;
  opacity: 0.7;
}
.line-light {
  stroke: var(--ui-accent-hover);
  stroke-width: 1;
  stroke-dasharray: 0.09 0.91;
  stroke-linecap: round;
  animation: line-travel 24s linear infinite;
}
.line-secondary {
  opacity: 0.6;
}
@keyframes line-travel {
  from {
    stroke-dashoffset: 1;
  }
  to {
    stroke-dashoffset: -1;
  }
}
@media (prefers-reduced-motion: reduce) {
  .line-light {
    animation: none;
    stroke-dashoffset: 0.35;
  }
}
</style>
