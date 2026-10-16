<template>
  <router-view />
</template>

<style>
/* 全局 border-box：避免 padding 撑破 max-height/width（如工作台侧栏 100vh+32px 把页面顶高） */
*,
*::before,
*::after {
  box-sizing: border-box;
}

html,
body,
#app {
  margin: 0;
  height: 100%;
  font-family: 'PingFang SC', 'Microsoft YaHei', 'Segoe UI', system-ui, sans-serif;
  background: #f3f6fb;
}
</style>
