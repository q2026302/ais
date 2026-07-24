<script setup lang="ts">
defineOptions({
  name: 'FeishuGalleryPage',
})
</script>

<template>
  <div class="placeholder-page">
    <h2>作品库</h2>
  </div>
</template>

<style scoped>
.placeholder-page {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
  padding: 24px;
  color: var(--mobile-muted, #7d899f);
}

.placeholder-page h2 {
  margin: 0;
  color: var(--mobile-text, #24314d);
  font-size: 18px;
  font-weight: 600;
}
</style>
