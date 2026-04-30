<template>
  <ion-app>
    <MainMenu content-id="main-content" :disabled="route.meta.menu !== true"></MainMenu>
    <ion-router-outlet id="main-content"/>
  </ion-app>
</template>

<script setup lang="ts">
import {createGesture, IonApp, IonRouterOutlet, menuController, type Gesture} from '@ionic/vue';
import {onBeforeUnmount, onMounted} from 'vue';
import MainMenu from "@/components/menu/MainMenu.vue";
import {useRoute} from "vue-router"

const route = useRoute()
let menuOpenGesture: Gesture | undefined
let menuCloseGesture: Gesture | undefined
let isMenuOpen = false

const handleMenuDidOpen = () => {
  isMenuOpen = true
}

const handleMenuDidClose = () => {
  isMenuOpen = false
}

onMounted(() => {
  const contentEl = document.getElementById('main-content')
  const menuEl = document.querySelector('ion-menu')
  if (!contentEl || !menuEl) return

  menuEl.addEventListener('ionDidOpen', handleMenuDidOpen)
  menuEl.addEventListener('ionDidClose', handleMenuDidClose)

  menuOpenGesture = createGesture({
    el: contentEl,
    gestureName: 'main-content-menu-open',
    gesturePriority: 29,
    threshold: 0,
    canStart: (detail) => {
      if (route.meta.menu !== true) return false
      if (detail.startX > window.innerWidth * 0.7) return false
      return true
    },
    onEnd: (detail) => {
      if (route.meta.menu !== true) return

      const isMostlyHorizontal = Math.abs(detail.deltaX) > Math.abs(detail.deltaY)
      const shouldOpen = detail.deltaX > 24 || detail.velocityX > 0.25

      if (!isMostlyHorizontal || !shouldOpen) return

      void menuController.isOpen().then((isOpen) => {
        if (isOpen) return
        void menuController.open()
      })
    },
  })

  menuOpenGesture.enable(true)

  menuCloseGesture = createGesture({
    el: menuEl,
    gestureName: 'main-content-menu-close',
    gesturePriority: 31,
    threshold: 0,
    canStart: () => {
      if (route.meta.menu !== true) return false
      return isMenuOpen
    },
    onEnd: (detail) => {
      if (!isMenuOpen) return

      const isMostlyHorizontal = Math.abs(detail.deltaX) > Math.abs(detail.deltaY)
      const shouldClose = detail.deltaX < -14 || detail.velocityX < -0.18

      if (!isMostlyHorizontal || !shouldClose) return
      void menuController.close()
    },
  })

  menuCloseGesture.enable(true)
})

onBeforeUnmount(() => {
  const menuEl = document.querySelector('ion-menu')
  menuEl?.removeEventListener('ionDidOpen', handleMenuDidOpen)
  menuEl?.removeEventListener('ionDidClose', handleMenuDidClose)

  menuOpenGesture?.destroy()
  menuOpenGesture = undefined
  menuCloseGesture?.destroy()
  menuCloseGesture = undefined
})
</script>
