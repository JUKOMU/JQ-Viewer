<template>
  <IonMenu :content-id="contentId">
    <IonHeader class="ion-no-border">
      <IonToolbar>
        <IonTitle>Menu Content</IonTitle>
      </IonToolbar>
    </IonHeader>
    <IonContent class="ion-padding">
      <IonList>
        <IonMenuToggle>
          <IonButton expand="block" router-link="/tabs/tab1" router-direction="root">
            <IonLabel>首页1</IonLabel>
          </IonButton>
        </IonMenuToggle>
        <IonMenuToggle>
          <IonButton expand="block" router-link="/tabs/tab2" router-direction="root">
            <IonLabel>搜索</IonLabel>
          </IonButton>
        </IonMenuToggle>
        <IonMenuToggle>
          <IonButton expand="block" router-link="/tabs/tab3" router-direction="root">
            <IonLabel>Tab3</IonLabel>
          </IonButton>
        </IonMenuToggle>
      </IonList>
    </IonContent>
  </IonMenu>
</template>

<script setup lang="ts">
defineProps({
  contentId: String,
});
</script>
