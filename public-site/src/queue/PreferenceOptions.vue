<script setup>
import { PREFERENCE_OPTIONS } from './queueActionAvailability.js'
import SoloPassTickBox from './SoloPassTickBox.vue'

// The play preferences as on the terminal's 更改游玩偏好: a title and what it means, the
// current choice marked. Where the host offers it, the 单人游玩 option holds the tick box that
// lets registrations behind pass over this one; it shows only while 单人游玩 is chosen, and
// choosing 允许他人加入 clears it.
const props = defineProps({
  modelValue: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
  passingAvailable: { type: Boolean, default: false },
  passing: { type: Boolean, default: false },
  // For the tick box's first explanation: whether the profile in use has been shown it, and
  // what identifies that profile on this page (SoloPassTickBox.vue).
  passingExplanationShown: { type: Boolean, default: false },
  passingExplanationKey: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue', 'update:passing'])

function choose(value) {
  emit('update:modelValue', value)
  if (value !== 'SOLO' && props.passing) emit('update:passing', false)
}
</script>

<template>
  <div class="preference-options">
    <div v-for="option in PREFERENCE_OPTIONS" :key="option.value"
      class="preference-option" :class="{ active: modelValue === option.value }">
      <button type="button" class="preference-option-choice"
        :aria-pressed="modelValue === option.value ? 'true' : 'false'" :disabled="disabled"
        @click="choose(option.value)">
        <span class="preference-option-title">{{ option.title }}</span>
        <span class="preference-option-description">{{ option.description }}</span>
      </button>
      <SoloPassTickBox v-if="option.value === 'SOLO' && passingAvailable && modelValue === 'SOLO'"
        :model-value="passing" :disabled="disabled"
        :explanation-shown="passingExplanationShown" :explanation-key="passingExplanationKey"
        @update:model-value="(value) => emit('update:passing', value)" />
    </div>
  </div>
</template>

<style scoped>
.preference-options { display: grid; gap: 6px; }
.preference-option {
  min-width: 0; border: 1px solid var(--queue-separator, #d2d2d7); border-radius: 9px;
  color: var(--queue-text, #1d1d1f); background: var(--queue-card, #fff);
}
.preference-option.active { border-color: var(--queue-blue, #007aff); background: var(--queue-soft-blue, #eaf3ff); }
.preference-option-choice {
  display: grid; width: 100%; gap: 3px; padding: 10px 12px; text-align: left; font: inherit; cursor: pointer;
  border: 0; border-radius: 9px; color: inherit; background: transparent;
}
.preference-option-title { font-size: 12px; font-weight: 620; }
.preference-option.active .preference-option-title { color: var(--queue-blue, #007aff); }
.preference-option-description { color: var(--queue-secondary, #6e6e73); font-size: 10px; line-height: 1.55; }
.preference-option-choice:disabled { cursor: default; }
.preference-option > .solo-pass-tick-box { margin: 0 12px 11px; }
</style>
