<script setup lang="ts">
</script>

<template>
  <div class="app">
    <nav class="nav">
      <router-link to="/embed">嵌入</router-link>
      <router-link to="/extract">提取</router-link>
    </nav>
    <main class="main">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.app {
  max-width: 800px;
  margin: 0 auto;
  padding: 20px;
}
.nav {
  display: flex;
  gap: 20px;
  margin-bottom: 20px;
  padding-bottom: 10px;
  border-bottom: 1px solid #eee;
}
.nav a {
  text-decoration: none;
  color: #333;
  font-weight: bold;
}
.nav a.router-link-active {
  color: #42b883;
}
</style>
