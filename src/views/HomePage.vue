<template>
  <MainMenu content-id="tabs-content"></MainMenu>
  <ion-page>
    <ion-toolbar>
      <ion-buttons slot="start">
        <ion-menu-button :auto-hide="false"></ion-menu-button>
      </ion-buttons>
    </ion-toolbar>
    <ion-tabs id="tabs-content">
      <ion-router-outlet></ion-router-outlet>
      <ion-tab-bar slot="bottom">
        <ion-tab-button tab="tab1" href="/tabs/tab1">
          <ion-icon aria-hidden="true" :icon="homeOutline"/>
          <ion-label>Tab 1</ion-label>
        </ion-tab-button>

        <ion-tab-button tab="tab2" href="/tabs/tab2">
          <ion-icon aria-hidden="true" :icon="ellipse"/>
          <ion-label>Tab 2</ion-label>
        </ion-tab-button>

        <ion-tab-button tab="tab3" href="/tabs/tab3">
          <ion-icon aria-hidden="true" :icon="square"/>
          <ion-label>Tab 3</ion-label>
        </ion-tab-button>
      </ion-tab-bar>
    </ion-tabs>
  </ion-page>
</template>

<script setup lang="ts">
import {
  IonButtons,
  IonIcon,
  IonLabel,
  IonMenuButton,
  IonPage,
  IonRouterOutlet,
  IonTabBar,
  IonTabButton,
  IonTabs,
  IonToolbar,
} from '@ionic/vue';
import {ellipse, homeOutline, square} from 'ionicons/icons';
import MainMenu from "@/components/MainMenu.vue";
</script>

<style scoped>
ion-tab-button {
  --ripple-color: rgb(255, 255, 255)
}
</style>
