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
      <IonTitle class="search-page-title">高级搜索</IonTitle>
      <SearchBar class="search-bar"></SearchBar>
    </IonContent>
  </IonPage>
</template>

<script setup lang="ts">
import {IonPage} from "@ionic/vue";
import SearchBar from "@/components/search/SearchBar.vue";
</script>

<style scoped>
.search-page-title {
  font-size: 30px;
  text-align: center;
  padding: 20px 0 30px 0;
  color: rgb(255 150 94);
}
</style>
