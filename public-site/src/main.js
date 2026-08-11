import { createApp } from 'vue'
import App from './App.vue'
import './styles.css'

const darkModeQuery = window.matchMedia('(prefers-color-scheme: dark)')

function applyColorScheme() {
  document.documentElement.classList.toggle('dark', darkModeQuery.matches)
  document.querySelector('meta[name="theme-color"]')?.setAttribute(
    'content',
    darkModeQuery.matches ? '#000000' : '#f5f5f7'
  )
}

applyColorScheme()
darkModeQuery.addEventListener?.('change', applyColorScheme)

createApp(App).mount('#app')
