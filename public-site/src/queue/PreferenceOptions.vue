<script setup>
import { PREFERENCE_OPTIONS } from './queueActionAvailability.js'

// The play preferences as on the terminal's 更改游玩偏好: a title and what it means, the
// current choice marked.
defineProps({
  modelValue: { type: String, default: '' },
  disabled: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])
</script>

<template>
  <div class="preference-options">
    <button v-for="option in PREFERENCE_OPTIONS" :key="option.value" type="button"
      class="preference-option" :class="{ active: modelValue === option.value }"
      :aria-pressed="modelValue === option.value ? 'true' : 'false'" :disabled="disabled"
      @click="emit('update:modelValue', option.value)">
      <span class="preference-option-title">{{ option.title }}</span>
      <span class="preference-option-description">{{ option.description }}</span>
    </button>
  </div>
</template>

<style scoped>
.preference-options { display: grid; gap: 6px; }
.preference-option {
  display: grid; gap: 3px; padding: 10px 12px; text-align: left; font: inherit; cursor: pointer;
  border: 1px solid var(--queue-separator, #d2d2d7); border-radius: 9px;
  color: var(--queue-text, #1d1d1f); background: var(--queue-card, #fff);
}
.preference-option.active { border-color: var(--queue-blue, #007aff); background: var(--queue-soft-blue, #eaf3ff); }
.preference-option-title { font-size: 12px; font-weight: 620; }
.preference-option.active .preference-option-title { color: var(--queue-blue, #007aff); }
.preference-option-description { color: var(--queue-secondary, #6e6e73); font-size: 10px; line-height: 1.55; }
.preference-option:disabled { cursor: default; }
</style>
