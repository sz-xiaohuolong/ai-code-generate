<script setup lang="ts">
// Hero 首屏纯净白底 + 居中天蓝呼吸光晕，仅驱动合成层 transform 与 opacity。
defineProps<{
  active?: boolean
}>()
</script>

<template>
  <div class="home-atmosphere" :class="{ 'is-active': active }" aria-hidden="true">
    <div class="halo-stage">
      <div class="halo-core" />
      <div class="halo-orbit halo-orbit-a" />
      <div class="halo-orbit halo-orbit-b" />
      <div class="halo-veil" />
    </div>
  </div>
</template>

<style scoped>
.home-atmosphere {
  --halo-opacity: 0.84;
  --halo-scale: 1;
  position: absolute;
  inset: 0;
  pointer-events: none;
  overflow: hidden;
  background: radial-gradient(120% 90% at 50% 42%, #ffffff 0%, var(--ui-shell) 100%);
  mask-image: linear-gradient(to bottom, #000 0%, #000 76%, transparent 100%);
  -webkit-mask-image: linear-gradient(to bottom, #000 0%, #000 76%, transparent 100%);
}

.home-atmosphere.is-active,
:global(.hero-section:has(.prompt-box:focus-within)) .home-atmosphere {
  --halo-opacity: 1;
  --halo-scale: 1.07;
}

.halo-stage {
  position: absolute;
  inset: 0;
  opacity: var(--halo-opacity);
  transform: scale(var(--halo-scale));
  transition:
    opacity 480ms cubic-bezier(0.16, 1, 0.3, 1),
    transform 560ms cubic-bezier(0.16, 1, 0.3, 1);
  will-change: opacity, transform;
}

.halo-core {
  position: absolute;
  top: 50%;
  left: 50%;
  width: min(1240px, 96vw);
  height: min(680px, 96%);
  margin-top: calc(min(680px, 96%) / -2);
  margin-left: calc(min(1240px, 96vw) / -2);
  border-radius: 50%;
  background: radial-gradient(
    ellipse at 50% 50%,
    rgba(162, 209, 255, 0.78) 0%,
    rgba(186, 222, 255, 0.62) 34%,
    rgba(214, 235, 255, 0.36) 58%,
    rgba(236, 245, 255, 0.12) 76%,
    transparent 90%
  );
  filter: blur(50px);
  animation: halo-breathe 11s cubic-bezier(0.45, 0.05, 0.55, 0.95) infinite alternate;
  will-change: transform, opacity;
}

.halo-orbit {
  position: absolute;
  top: 50%;
  left: 50%;
  border-radius: 50%;
  filter: blur(58px);
  will-change: transform;
}

.halo-orbit-a {
  width: min(780px, 74vw);
  height: min(480px, 70%);
  margin-top: calc(min(480px, 70%) / -2);
  margin-left: calc(min(780px, 74vw) / -2);
  background: radial-gradient(
    ellipse at 58% 46%,
    rgba(134, 193, 255, 0.58) 0%,
    rgba(186, 219, 255, 0.34) 48%,
    transparent 76%
  );
  animation: halo-drift-a 18s linear infinite;
}

.halo-orbit-b {
  width: min(740px, 70vw);
  height: min(460px, 68%);
  margin-top: calc(min(460px, 68%) / -2);
  margin-left: calc(min(740px, 70vw) / -2);
  background: radial-gradient(
    ellipse at 42% 54%,
    rgba(122, 196, 255, 0.48) 0%,
    rgba(199, 228, 255, 0.28) 48%,
    transparent 76%
  );
  animation: halo-drift-b 24s linear infinite;
}

.halo-veil {
  position: absolute;
  top: 46%;
  left: 50%;
  width: min(560px, 64vw);
  height: min(260px, 44%);
  margin-top: calc(min(260px, 44%) / -2);
  margin-left: calc(min(560px, 64vw) / -2);
  border-radius: 50%;
  background: radial-gradient(
    ellipse at 50% 50%,
    rgba(255, 255, 255, 0.26) 0%,
    rgba(255, 255, 255, 0.08) 54%,
    transparent 80%
  );
  filter: blur(24px);
}

@keyframes halo-breathe {
  from {
    transform: translate3d(0, -6px, 0) scale(0.96);
    opacity: 0.88;
  }
  to {
    transform: translate3d(0, 8px, 0) scale(1.05);
    opacity: 1;
  }
}

@keyframes halo-drift-a {
  0% {
    transform: rotate(0deg) translate3d(38px, -14px, 0) rotate(0deg);
  }
  50% {
    transform: rotate(180deg) translate3d(44px, 16px, 0) rotate(-180deg);
  }
  100% {
    transform: rotate(360deg) translate3d(38px, -14px, 0) rotate(-360deg);
  }
}

@keyframes halo-drift-b {
  0% {
    transform: rotate(360deg) translate3d(-42px, 12px, 0) rotate(-360deg);
  }
  50% {
    transform: rotate(180deg) translate3d(-34px, -18px, 0) rotate(-180deg);
  }
  100% {
    transform: rotate(0deg) translate3d(-42px, 12px, 0) rotate(0deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .halo-core,
  .halo-orbit-a,
  .halo-orbit-b {
    animation: none;
    transform: none;
  }
}
</style>
