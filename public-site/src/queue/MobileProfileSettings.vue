<script setup>
defineProps({
  settings: { type: Object, required: true },
  botQq: { type: String, default: null },
  showVisibility: { type: Boolean, default: true }
})

const emit = defineEmits(['change'])

const notificationOptions = [
  ['notify_queue_changes', '队列状态变化'],
  ['notify_online_check_in', '线上登记与签到'],
  ['notify_absence', '暂缓一次、暂时离开和未到场'],
  ['notify_playing_position', '游玩位置变化'],
  ['notify_machine_status', '机台及营业状态']
]

function update(key, value) {
  emit('change', { key, value })
}
</script>

<template>
  <fieldset v-if="showVisibility">
    <legend>QQ 显示范围</legend>
    <div class="mobile-choice-row">
      <button type="button" :class="{ active: settings.qq_visibility === 'TERMINAL_ONLY' }"
        @click="update('qq_visibility', 'TERMINAL_ONLY')">仅终端显示</button>
      <button type="button" :class="{ active: settings.qq_visibility === 'PUBLIC_WEBSITE' }"
        @click="update('qq_visibility', 'PUBLIC_WEBSITE')">允许网站显示</button>
    </div>
  </fieldset>
  <fieldset class="mobile-notifications">
    <legend>排队通知</legend>
    <label class="mobile-toggle is-master">
      <span>
        <strong>排队通知</strong>
        <small>需要添加 QQ Bot<template v-if="botQq">（{{ botQq }}）</template>为好友，才能接收主动私信。</small>
      </span>
      <input type="checkbox" :checked="settings.notification_enabled"
        @change="update('notification_enabled', $event.target.checked)" />
    </label>
    <label v-for="option in notificationOptions" :key="option[0]" class="mobile-toggle">
      <span>{{ option[1] }}</span>
      <input type="checkbox" :checked="settings[option[0]]" :disabled="!settings.notification_enabled"
        @change="update(option[0], $event.target.checked)" />
    </label>
  </fieldset>
</template>

<style scoped>
fieldset { min-width: 0; margin: 0; padding: 0; border: 0; }
legend { margin-bottom: 8px; padding: 0; color: var(--mobile-secondary); font-size: 13px; font-weight: 610; }
.mobile-choice-row { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.mobile-choice-row button { min-height: 44px; padding: 8px 10px; border: 1px solid var(--mobile-separator); border-radius: 8px; color: var(--mobile-text); background: var(--mobile-card); cursor: pointer; font-size: 13px; }
.mobile-choice-row button.active { border-color: color-mix(in srgb, var(--mobile-blue) 62%, var(--mobile-separator)); color: var(--mobile-blue); background: var(--mobile-soft-blue); font-weight: 620; }
.mobile-notifications { overflow: hidden; border: 1px solid var(--mobile-separator); border-radius: 8px; background: var(--mobile-card); }
.mobile-notifications > legend { margin: 0 12px; padding: 0 5px; }
.mobile-toggle { display: flex; min-height: 48px; padding: 10px 13px; align-items: center; justify-content: space-between; gap: 14px; border-bottom: 1px solid var(--mobile-separator); }
.mobile-toggle:last-child { border-bottom: 0; }
.mobile-toggle > span { color: var(--mobile-text); font-size: 13px; font-weight: 580; }
.mobile-toggle > span strong, .mobile-toggle > span small { display: block; }
.mobile-toggle > span small { max-width: 50em; margin-top: 2px; color: var(--mobile-secondary); font-size: 11px; font-weight: 400; line-height: 1.5; }
.mobile-toggle input { width: 18px; height: 18px; flex: 0 0 auto; accent-color: var(--mobile-blue); }
.mobile-toggle input:disabled { opacity: .45; }
</style>
