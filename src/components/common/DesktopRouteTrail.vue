<template>
  <nav class="desktop-route-trail" aria-label="页面路径">
    <ol class="desktop-route-trail-list">
      <li
        v-for="(item, index) in items"
        :key="item.isEllipsis ? `ellipsis-${index}` : `${item.path}-${index}`"
        class="desktop-route-trail-item"
      >
        <span v-if="index > 0" class="desktop-route-trail-separator" aria-hidden="true">›</span>
        <span
          v-if="item.isEllipsis"
          class="desktop-route-trail-ellipsis"
          aria-label="省略的历史路径"
        >
          {{ item.label }}
        </span>
        <RouterLink
          v-else
          :class="[
            'desktop-route-trail-link',
            { 'desktop-route-trail-current': index === items.length - 1 },
          ]"
          :to="item.path"
          :aria-label="`返回${item.label}`"
          :aria-current="index === items.length - 1 ? 'page' : undefined"
          @click="onTrailItemClick($event, item.path, item.trailIndex)"
        >
          {{ item.label }}
        </RouterLink>
      </li>
    </ol>
  </nav>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { buildDesktopRouteTrail } from '@/utils/desktopRouteTrail'

defineOptions({ name: 'DesktopRouteTrail' })

const props = defineProps<{
  routeStack: readonly string[]
  currentPath: string
}>()

const emit = defineEmits<{
  navigate: [payload: { path: string; trailIndex: number }]
}>()

const items = computed(() => buildDesktopRouteTrail(props.routeStack, props.currentPath))

const onTrailItemClick = (event: MouseEvent, path: string | null, trailIndex: number | null) => {
  if (!path || trailIndex === null) return
  event.preventDefault()
  emit('navigate', { path, trailIndex })
}
</script>

<style scoped>
.desktop-route-trail {
  box-sizing: border-box;
  flex: 0 0 38px;
  width: 100%;
  min-width: 0;
  padding: 0 20px;
  border-bottom: 1px solid #f1e5dc;
  background: rgb(255 252 249 / 0.96);
  color: #8d6c59;
  overflow-x: auto;
  overflow-y: hidden;
}

.desktop-route-trail-list {
  display: flex;
  align-items: center;
  width: max-content;
  min-width: 100%;
  height: 100%;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
  white-space: nowrap;
}

.desktop-route-trail-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  font-size: 13px;
}

.desktop-route-trail-separator {
  color: #cbb4a4;
  font-size: 17px;
  line-height: 1;
}

.desktop-route-trail-link,
.desktop-route-trail-current,
.desktop-route-trail-ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
}

.desktop-route-trail-link {
  display: inline-block;
  max-width: 180px;
  padding: 3px 5px;
  border-radius: 4px;
  color: #9b6749;
  font: inherit;
  text-decoration: none;
}

.desktop-route-trail-link:hover {
  background: #fff0e7;
  color: #cf6f3c;
}

.desktop-route-trail-link:focus-visible {
  outline: 2px solid #e8843c;
  outline-offset: 2px;
}

.desktop-route-trail-current {
  max-width: 240px;
  color: #4c2a18;
  font-weight: 600;
}

.desktop-route-trail-current:hover {
  background: #fff0e7;
  color: #4c2a18;
}

.desktop-route-trail-ellipsis {
  color: #b89a84;
  font-size: 16px;
}
</style>
