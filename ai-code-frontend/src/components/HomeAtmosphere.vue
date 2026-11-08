<script setup lang="ts">
// 流线只装饰创作舞台，SVG 与 CSS 保持零运行时动画依赖。
const paths = Array.from({ length: 7 }, (_, i) => {
  const offset = i * 26
  return `M ${-120 + offset} -80 C ${80 + offset} 80, ${80 + offset} 380, 580 375 S ${1030 - offset} 170, ${1540 - offset} 680`
})
</script>

<template>
  <div class="home-atmosphere" aria-hidden="true">
    <div class="stage-grid" />
    <div class="spotlight spotlight-main" />
    <div class="spotlight spotlight-side" />
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
  background: #080d18;
}
.stage-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(#c7dcff08 1px, transparent 1px),
    linear-gradient(90deg, #c7dcff08 1px, transparent 1px);
  background-size: 64px 64px;
  mask-image: radial-gradient(ellipse at 50% 28%, #000 12%, transparent 77%);
}
.spotlight {
  position: absolute;
  top: -550px;
  left: calc(50% - 660px);
  width: 610px;
  height: 1160px;
  transform: rotate(-36deg);
  border-radius: 50%;
  filter: blur(38px);
  background: radial-gradient(ellipse, #d4e7ff30 0%, #9fc8ff18 32%, #5f97dc08 54%, transparent 68%);
}
.spotlight-side {
  left: calc(50% + 240px);
  top: -590px;
  width: 380px;
  transform: rotate(32deg);
  opacity: 0.5;
}
.background-lines {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  mask-image: linear-gradient(#000b, #000 55%, transparent);
}
.line-track {
  stroke: #84b7ff;
  stroke-width: 0.6;
  opacity: 0.1;
}
.line-light {
  stroke: #a8d4ff;
  stroke-width: 1;
  stroke-dasharray: 0.09 0.91;
  stroke-linecap: round;
  opacity: 0.32;
  animation: line-travel 24s linear infinite;
}
.line-secondary {
  stroke: #83b7d9;
  opacity: 0.2;
}
@keyframes line-travel {
  from {
    stroke-dashoffset: 1;
  }
  to {
    stroke-dashoffset: -1;
  }
}
@media (max-width: 640px) {
  .spotlight-main {
    left: calc(50% - 410px);
    width: 480px;
  }
  .spotlight-side {
    display: none;
  }
  .background-lines {
    opacity: 0.6;
  }
}
@media (prefers-reduced-motion: reduce) {
  .line-light {
    animation: none;
    stroke-dashoffset: 0.35;
  }
}
</style>
