<script setup lang="ts">
import { useRoute } from 'vue-router'

defineOptions({
  name: 'FeishuChatPage',
})

const route = useRoute()
</script>

<template>
  <div class="placeholder-page">
    <div class="placeholder-icon">🤖</div>
    <h2>对话页</h2>
    <p class="placeholder-hint">会话 ID: {{ route.params.id }}</p>
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
.placeholder-icon {
  font-size: 48px;
  margin-bottom: 12px;
  opacity: .6;
}
.placeholder-page h2 {
  margin: 0 0 8px;
  color: var(--mobile-text, #24314d);
  font-size: 18px;
  font-weight: 600;
}
.placeholder-hint {
  margin: 0;
  font-size: 13px;
}
</style>
