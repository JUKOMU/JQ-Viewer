<template>
  <button type="button" class="menu-toggle-btn" @click="openMenu">
    <IonIcon :icon="menuOutline"/>
  </button>
</template>

<script setup lang="ts">
import {IonIcon, menuController} from '@ionic/vue'
import {menuOutline} from 'ionicons/icons'

const openMenu = () => {
  void menuController.open()
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
