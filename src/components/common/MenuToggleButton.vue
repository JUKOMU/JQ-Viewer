<template>
  <button
    v-if="!isWideMenu"
    type="button"
    class="menu-toggle-btn"
    aria-label="打开侧边栏"
    @click="openMenu"
  >
    <IonIcon :icon="menuOutline" />
  </button>
</template>

<script setup lang="ts">
defineOptions({ name: 'MenuToggleButton' })

import { IonIcon } from '@ionic/vue'
import { menuOutline } from 'ionicons/icons'
import { isWideMenu, openLeftMenu } from '@/composables/useSideMenuState'

const openMenu = () => {
  openLeftMenu()
}
</script>

<style scoped>
.menu-toggle-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  border: 0;
  border-radius: 15px;
  background: #fff3ea;
  color: #7f553b;
  font-size: 20px;
  box-shadow: -1px 5px 5px 0 rgb(76 42 24 / 0.08);
}
</style>
