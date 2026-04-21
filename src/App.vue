<template>
  <ion-app>
    <MainMenu content-id="main-content" :disabled="route.meta.menu !== true"></MainMenu>
    <ion-router-outlet id="main-content"/>
  </ion-app>
</template>

<script setup lang="ts">
import {IonApp, IonRouterOutlet} from '@ionic/vue';
import MainMenu from "@/components/menu/MainMenu.vue";
import {useRoute} from "vue-router"

const route = useRoute()
</script>
