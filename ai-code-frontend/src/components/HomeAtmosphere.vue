<script setup lang="ts">
// Hero 首屏动态蓝白氛围层：仅使用 CSS 合成层旋转与透明度渐变，保持零运行时动画开销。
defineProps<{
  active?: boolean
}>()

const paths = Array.from({ length: 7 }, (_, i) => {
  const offset = i * 26
  return `M ${-120 + offset} -80 C ${80 + offset} 80, ${80 + offset} 380, 580 375 S ${1030 - offset} 170, ${1540 - offset} 680`
})
</script>

<template>
  <div class="home-atmosphere" :class="{ 'is-active': active }" aria-hidden="true">
    <!-- 深空基底上的非对称冷蓝/冰白光场，避免纯平背景 -->
    <div class="aura-field" />

    <!-- 四周向内漫射的蓝白边缘光晕：中心保留深色高可读创作区 -->
    <div class="aura-edge-bloom">
      <div class="aura-conic aura-conic-primary" />
      <div class="aura-conic aura-conic-counter" />
    </div>

    <!-- 贴合视口左右与上缘的细微蓝白流光边线，顶部柔和过渡避免与顶栏产生硬线重叠 -->
    <div class="aura-edge-trace">
      <div class="aura-conic aura-conic-trace" />
    </div>

    <!-- 极简几何流线作为底层工程纹理 -->
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
  --aura-bloom-opacity: 0.62;
  --aura-counter-opacity: 0.34;
  --aura-trace-opacity: 0.58;
  --aura-lines-opacity: 0.1;
  position: absolute;
  inset: 0;
  pointer-events: none;
  overflow: hidden;
  background: var(--ui-shell);
  mask-image: linear-gradient(to bottom, #000 0%, #000 72%, transparent 100%);
  -webkit-mask-image: linear-gradient(to bottom, #000 0%, #000 72%, transparent 100%);
}

.home-atmosphere.is-active,
:global(.hero-section:has(.prompt-box:focus-within)) .home-atmosphere {
  --aura-bloom-opacity: 0.94;
  --aura-counter-opacity: 0.62;
  --aura-trace-opacity: 0.92;
  --aura-lines-opacity: 0.18;
}

.aura-field {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(48% 58% at 4% 28%, rgba(91, 149, 255, 0.24) 0%, transparent 72%),
    radial-gradient(46% 54% at 96% 24%, rgba(224, 242, 254, 0.16) 0%, transparent 70%),
    radial-gradient(62% 38% at 50% -6%, rgba(160, 193, 255, 0.14) 0%, transparent 74%);
  opacity: 0.9;
  transition: opacity 420ms cubic-bezier(0.16, 1, 0.3, 1);
}

.aura-edge-bloom {
  position: absolute;
  inset: -24px;
  filter: blur(48px);
  mask-image: radial-gradient(
    ellipse 72% 62% at 50% 50%,
    transparent 46%,
    rgba(0, 0, 0, 0.38) 74%,
    #000 100%
  );
  -webkit-mask-image: radial-gradient(
    ellipse 72% 62% at 50% 50%,
    transparent 46%,
    rgba(0, 0, 0, 0.38) 74%,
    #000 100%
  );
  opacity: var(--aura-bloom-opacity);
  transition: opacity 420ms cubic-bezier(0.16, 1, 0.3, 1);
}

.aura-edge-trace {
  position: absolute;
  inset: 0;
  padding: 0 2px 2px;
  mask:
    linear-gradient(#fff 0 0) content-box,
    linear-gradient(#fff 0 0);
  -webkit-mask:
    linear-gradient(#fff 0 0) content-box,
    linear-gradient(#fff 0 0);
  mask-composite: exclude;
  -webkit-mask-composite: xor;
  opacity: var(--aura-trace-opacity);
  transition: opacity 420ms cubic-bezier(0.16, 1, 0.3, 1);
}

.aura-conic {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 185vmax;
  height: 185vmax;
  margin-top: -92.5vmax;
  margin-left: -92.5vmax;
  will-change: transform;
}

.aura-conic-primary {
  background: conic-gradient(
    from 0deg,
    rgba(91, 149, 255, 0.08) 0deg,
    rgba(91, 149, 255, 0.68) 52deg,
    rgba(240, 248, 255, 0.94) 92deg,
    rgba(160, 193, 255, 0.74) 132deg,
    rgba(91, 149, 255, 0.1) 192deg,
    rgba(122, 167, 255, 0.62) 252deg,
    rgba(255, 255, 255, 0.9) 292deg,
    rgba(91, 149, 255, 0.44) 334deg,
    rgba(91, 149, 255, 0.08) 360deg
  );
  animation: aura-orbit 15s linear infinite;
}

.aura-conic-counter {
  opacity: var(--aura-counter-opacity);
  background: conic-gradient(
    from 180deg,
    transparent 0deg,
    rgba(240, 248, 255, 0.78) 45deg,
    rgba(91, 149, 255, 0.48) 110deg,
    transparent 180deg,
    rgba(186, 230, 253, 0.72) 235deg,
    rgba(91, 149, 255, 0.38) 300deg,
    transparent 360deg
  );
  transition: opacity 420ms cubic-bezier(0.16, 1, 0.3, 1);
  animation: aura-orbit-reverse 21s linear infinite;
}

.aura-conic-trace {
  background: conic-gradient(
    from 35deg,
    rgba(122, 167, 255, 0.1) 0deg,
    rgba(91, 149, 255, 0.72) 40deg,
    rgba(255, 255, 255, 0.96) 72deg,
    rgba(160, 193, 255, 0.72) 108deg,
    rgba(122, 167, 255, 0.1) 170deg,
    rgba(91, 149, 255, 0.58) 225deg,
    rgba(240, 248, 255, 0.92) 260deg,
    rgba(122, 167, 255, 0.4) 305deg,
    rgba(122, 167, 255, 0.1) 360deg
  );
  animation: aura-orbit 12s linear infinite;
}

.background-lines {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: var(--aura-lines-opacity);
  transition: opacity 420ms cubic-bezier(0.16, 1, 0.3, 1);
  mask-image: linear-gradient(var(--ui-ink), var(--ui-ink) 55%, transparent);
}

.line-track {
  stroke: var(--ui-accent);
  stroke-width: 0.6;
  opacity: 0.45;
}

.line-light {
  stroke: #e6f2ff;
  stroke-width: 1.05;
  stroke-dasharray: 0.09 0.91;
  stroke-linecap: round;
  animation: line-travel 24s linear infinite;
}

.line-secondary {
  stroke: var(--ui-accent-hover);
  opacity: 0.65;
}

@keyframes aura-orbit {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

@keyframes aura-orbit-reverse {
  from {
    transform: rotate(360deg);
  }
  to {
    transform: rotate(0deg);
  }
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
  .aura-conic-primary,
  .aura-conic-counter,
  .aura-conic-trace {
    animation: none;
    transform: rotate(24deg);
  }

  .line-light {
    animation: none;
    stroke-dashoffset: 0.35;
  }
}
</style>
