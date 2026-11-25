<script setup>
import { CircleQuestionMark } from '@lucide/vue'
import { computed, ref } from 'vue'
import PairedQueueIntroduction from './PairedQueueIntroduction.vue'
import {
  PAIRED_QUEUE_DETAILS_BUTTON_LABEL,
  PAIRED_QUEUE_FIXED_PAIR_REASON,
  PAIRED_QUEUE_LATER_NOTE,
  PAIRED_QUEUE_TICK_BOX_NOTE,
  pairedQueueExplanationShownHere,
  pairedQueueTickBoxLabel,
  rememberPairedQueueExplanationShown
} from './pairedQueueCopy.js'

// Seconds the explanation stays open when it opens by itself.
const FIRST_USE_COUNTDOWN_SECONDS = 5

// The only switch for letting the system arrange a registration between the two machines of a
// pair. It belongs to the play preference of one registration and is off unless the player
// turns it on. The first time a player turns it on, the explanation opens by itself and can
// be closed after five seconds; the question mark opens the same explanation at any time.
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  pairedMachineName: { type: String, required: true },
  disabled: { type: Boolean, default: false },
  // A fixed pair plays at its own machine only: the box cannot be turned on for it.
  fixedPair: { type: Boolean, default: false },
  // Turning the box on for a registration that already waits only counts after that
  // registration has played a round; the note under the box then says so.
  takesEffectAfterARound: { type: Boolean, default: false },
  // Whether the player profile in use has already been shown the explanation, as the server
  // reports it, and what identifies that profile on this page.
  explanationShown: { type: Boolean, default: false },
  explanationKey: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue'])

const detailsOpen = ref(false)
const detailsCountdown = ref(0)
const label = computed(() => pairedQueueTickBoxLabel(props.pairedMachineName))
const blockedByFixedPair = computed(() => props.fixedPair && !props.modelValue)
const note = computed(() => {
  if (blockedByFixedPair.value) return PAIRED_QUEUE_FIXED_PAIR_REASON
  if (props.takesEffectAfterARound) return PAIRED_QUEUE_LATER_NOTE
  return PAIRED_QUEUE_TICK_BOX_NOTE
})

function change(event) {
  const turnedOn = event.target.checked
  emit('update:modelValue', turnedOn)
  if (
    turnedOn &&
    !props.explanationShown &&
    !pairedQueueExplanationShownHere(props.explanationKey)
  ) {
    rememberPairedQueueExplanationShown(props.explanationKey)
    openDetails(FIRST_USE_COUNTDOWN_SECONDS)
  }
}

function openDetails(countdownSeconds) {
  detailsCountdown.value = countdownSeconds
  detailsOpen.value = true
}
</script>

<template>
  <div class="paired-tick-box">
    <div class="paired-tick-box-row">
      <label :class="{ 'is-disabled': disabled || blockedByFixedPair }">
        <input type="checkbox" :checked="modelValue" :disabled="disabled || blockedByFixedPair"
          @change="change" />
        <span>{{ label }}</span>
      </label>
      <button type="button" class="paired-tick-box-help" :aria-label="PAIRED_QUEUE_DETAILS_BUTTON_LABEL"
        :title="PAIRED_QUEUE_DETAILS_BUTTON_LABEL" @click="openDetails(0)">
        <CircleQuestionMark :size="18" aria-hidden="true" />
      </button>
    </div>
    <small>{{ note }}</small>
    <PairedQueueIntroduction v-if="detailsOpen" :paired-machine-name="pairedMachineName"
      :countdown-seconds="detailsCountdown" @close="detailsOpen = false" />
  </div>
</template>

<style scoped>
.paired-tick-box { display: block; min-width: 0; margin-top: 10px; text-align: left; }
.paired-tick-box:first-child { margin-top: 0; }
.paired-tick-box-row { display: flex; align-items: center; gap: 6px; }
.paired-tick-box-row label { display: flex; min-width: 0; flex: 1; align-items: flex-start; gap: 8px; cursor: pointer; font-size: 13px; font-weight: 580; line-height: 1.5; }
.paired-tick-box-row label.is-disabled { cursor: default; opacity: .55; }
.paired-tick-box-row input { width: 17px; height: 17px; flex: none; margin: 2px 0 0; accent-color: var(--queue-blue, #007aff); }
.paired-tick-box-help { display: grid; width: 34px; height: 34px; flex: none; place-items: center; padding: 0; border: 0; border-radius: 50%; color: var(--queue-blue, #007aff); background: transparent; cursor: pointer; }
.paired-tick-box small { display: block; margin-top: 4px; color: var(--queue-secondary, var(--mobile-secondary, #6e6e73)); font-size: 11px; font-weight: 400; line-height: 1.55; }
</style>
