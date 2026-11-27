<script setup>
import { computed, ref } from 'vue'
import {
  REGISTRATION_RENAME_TEXT,
  limitRegistrationName,
  registrationNameTaken
} from './queueActionAvailability.js'

// 修改登记昵称, as the terminal's dialog of that name: the nickname this one registration shows,
// not the profile's. The field starts from the current nickname and takes at most 18 characters;
// a nickname that another registration of the queue shows is told under the field, and 保存昵称
// is grey for it and for an empty field, with the terminal's reasons. The terminal checks again.
// The account dialog and the registration detail of the status page both show this component.
const props = defineProps({
  // The player's own registration as the account API reports it.
  registration: { type: Object, required: true },
  // The registrations of the public queue (queueActionAvailability.js, `pageRegistrations`).
  pageRegistrations: { type: Array, default: () => [] },
  disabled: { type: Boolean, default: false }
})
const emit = defineEmits(['cancel', 'save'])

const draft = ref(String(props.registration.display_id || ''))
const reason = ref('')
const taken = computed(() => (
  registrationNameTaken(draft.value, props.registration, props.pageRegistrations)
))
const unavailableReason = computed(() => {
  if (!draft.value.trim()) return REGISTRATION_RENAME_TEXT.missing
  return taken.value ? REGISTRATION_RENAME_TEXT.taken : ''
})

function handleInput(event) {
  draft.value = limitRegistrationName(event.target.value)
  if (event.target.value !== draft.value) event.target.value = draft.value
  reason.value = ''
}

function save() {
  if (props.disabled) return
  if (unavailableReason.value) {
    reason.value = unavailableReason.value
    return
  }
  reason.value = ''
  emit('save', draft.value.trim())
}
</script>

<template>
  <!-- A plain element at the root: the hosts style their own forms. -->
  <div class="registration-rename">
    <strong class="registration-rename-title">{{ REGISTRATION_RENAME_TEXT.title }}</strong>
    <p class="registration-rename-intro">{{ REGISTRATION_RENAME_TEXT.intro }}</p>
    <label class="registration-rename-field" :class="{ 'is-error': taken }">
      <span>{{ REGISTRATION_RENAME_TEXT.label }}</span>
      <input type="text" :value="draft" :disabled="disabled" :placeholder="REGISTRATION_RENAME_TEXT.placeholder"
        autocomplete="off" :aria-invalid="taken ? 'true' : 'false'" @input="handleInput"
        @keydown.enter.prevent="save" />
      <small>{{ taken ? REGISTRATION_RENAME_TEXT.takenHint : REGISTRATION_RENAME_TEXT.hint }}</small>
    </label>
    <p v-if="reason" class="registration-rename-reason" role="alert">{{ reason }}</p>
    <div class="registration-rename-actions">
      <button type="button" :disabled="disabled" @click="emit('cancel')">取消</button>
      <button type="button" class="primary" :class="{ 'is-unavailable': unavailableReason }"
        :aria-disabled="unavailableReason ? 'true' : 'false'" :disabled="disabled" @click="save">
        {{ REGISTRATION_RENAME_TEXT.save }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.registration-rename { display: grid; min-width: 0; gap: 9px; }
.registration-rename-title { font-size: 12px; font-weight: 620; color: var(--queue-text, #1d1d1f); }
.registration-rename-intro { margin: 0; color: var(--queue-secondary, #6e6e73); font-size: 11px; line-height: 1.65; }
.registration-rename-field { display: grid; gap: 5px; }
.registration-rename-field > span { margin: 0; color: var(--queue-secondary, #6e6e73); font-size: 11px; font-weight: 620; }
.registration-rename-field input {
  width: 100%; height: 44px; padding: 0 12px; border: 1px solid var(--queue-separator, #d2d2d7); border-radius: 8px;
  color: var(--queue-text, #1d1d1f); background: var(--queue-card, #fff); font: inherit; font-size: 14px;
}
.registration-rename-field input:focus { border-color: var(--queue-blue, #007aff); outline: none; }
.registration-rename-field.is-error input { border-color: var(--queue-red, #c9342e); }
.registration-rename-field small { color: var(--queue-secondary, #6e6e73); font-size: 10px; line-height: 1.5; }
.registration-rename-field.is-error small, .registration-rename-reason { color: var(--queue-red, #c9342e); }
.registration-rename-reason { margin: 0; font-size: 10px; line-height: 1.55; }
.registration-rename-actions { display: grid; grid-template-columns: .8fr 1.2fr; gap: 6px; }
.registration-rename-actions button {
  min-height: 42px; padding: 0 9px; border: 0; border-radius: 7px; cursor: pointer; font-size: 10px; font-weight: 620;
  color: var(--queue-text, #1d1d1f); background: var(--queue-position, var(--queue-soft, #f5f5f7));
}
.registration-rename-actions button.primary { color: #fff; background: var(--queue-blue, #007aff); }
.registration-rename-actions button.primary.is-unavailable, .registration-rename-actions button:disabled {
  color: var(--queue-tertiary, #8e8e93); background: var(--queue-disabled, #e8e8ed);
}
.registration-rename-actions button:disabled { cursor: default; }
</style>
