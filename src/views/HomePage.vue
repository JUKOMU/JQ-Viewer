<template>
  <IonPage>
    <IonHeader class="ion-no-border">
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-menu-button :auto-hide="false"></ion-menu-button>
        </ion-buttons>
      </ion-toolbar>
    </IonHeader>
    <IonContent>
      <SearchBar class="search-bar" :mode-select="false" :filter-select="false"></SearchBar>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
import {IonPage} from "@ionic/vue";
import SearchBar from "@/components/search/SearchBar.vue";
</script>

<style scoped>
.search-bar {
  position: absolute;
  top: 25%;
  right: 0;
  left: 0;
  margin: auto;
}
</style>
