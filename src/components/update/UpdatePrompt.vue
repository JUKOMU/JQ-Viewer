<template>
  <IonPage class="update-prompt-page">
    <section class="update-prompt-shell" aria-labelledby="update-prompt-title">
      <header class="update-prompt-header">
        <p class="update-prompt-eyebrow">发现新版本</p>
        <h2 id="update-prompt-title">{{ manifest.versionName }}</h2>
      </header>

      <div class="update-prompt-notes" aria-label="发布说明">
        <!-- eslint-disable-next-line vue/no-v-html -- Markdown is sanitized before rendering. -->
        <div v-if="renderedNotes" class="release-markdown" v-html="renderedNotes" />
        <p v-else class="update-prompt-empty">暂无发布说明</p>
      </div>

      <footer class="update-prompt-actions">
        <button class="update-prompt-button secondary" type="button" @click="dismiss('cancel')">
          {{ cancelText }}
        </button>
        <button class="update-prompt-button primary" type="button" @click="dismiss('confirm')">
          {{ confirmText }}
        </button>
      </footer>
    </section>
  </IonPage>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { IonPage, modalController } from '@ionic/vue'
import type { UpdateManifest } from '@/services/JmcomicTypes'
import { renderReleaseNotesMarkdown } from '@/utils/releaseNotesMarkdown'

const props = withDefaults(
  defineProps<{
    manifest: UpdateManifest
    cancelText?: string
    confirmText?: string
  }>(),
  {
    cancelText: '忽略',
    confirmText: '下载更新',
  },
)

const renderedNotes = computed(() =>
  renderReleaseNotesMarkdown(props.manifest.releaseNotes, props.manifest.versionName),
)

async function dismiss(role: 'cancel' | 'confirm') {
  await modalController.dismiss(undefined, role)
}
</script>

<style scoped>
.update-prompt-page {
  position: relative;
  height: 100%;
  background: #fffbf8;
  color: #3a261d;
}

.update-prompt-shell {
  display: flex;
  height: 100%;
  min-height: 0;
  flex-direction: column;
}

.update-prompt-header {
  flex: 0 0 auto;
  padding: 24px 22px 18px;
  border-bottom: 1px solid #f0e4da;
}

.update-prompt-eyebrow {
  margin: 0 0 6px;
  color: #9a7968;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.update-prompt-header h2 {
  margin: 0;
  color: #4c2a18;
  font-size: 24px;
  font-weight: 650;
  line-height: 1.2;
}

.update-prompt-notes {
  min-height: 0;
  flex: 1 1 auto;
  padding: 20px 22px;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.update-prompt-empty {
  margin: 0;
  color: #8c6b5a;
  line-height: 1.6;
}

.update-prompt-actions {
  display: grid;
  flex: 0 0 auto;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  padding: 14px 16px 16px;
  border-top: 1px solid #f0e4da;
  background: #fffbf8;
}

.update-prompt-button {
  min-height: 44px;
  border: 0;
  border-radius: 10px;
  font: inherit;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition:
    background-color 0.2s ease,
    color 0.2s ease,
    transform 0.2s ease;
}

.update-prompt-button:active {
  transform: translateY(1px);
}

.update-prompt-button:focus-visible {
  outline: 2px solid #e8843c;
  outline-offset: 2px;
}

.update-prompt-button.secondary {
  background: #f5ebe4;
  color: #6b4e3e;
}

.update-prompt-button.secondary:hover {
  background: #eee0d6;
}

.update-prompt-button.primary {
  background: #e8843c;
  color: #fff;
}

.update-prompt-button.primary:hover {
  background: #d87531;
}

@media (prefers-reduced-motion: reduce) {
  .update-prompt-button {
    transition: none;
  }
}
</style>
