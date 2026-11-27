<script setup>
import { CircleQuestionMark } from '@lucide/vue'
import { computed, ref } from 'vue'
import PairedQueueIntroduction from './PairedQueueIntroduction.vue'
import { PAIRED_QUEUE_DETAILS_BUTTON_LABEL } from './pairedQueueCopy.js'
import {
  FIXED_PAIR_PASS_TICK_BOX_LABEL,
  FIXED_PAIR_PASS_TICK_BOX_NOTE,
  PASSING_DETAILS_TITLE,
  SOLO_PASS_TICK_BOX_LABEL,
  SOLO_PASS_TICK_BOX_NOTE,
  passingDetailsBlocks,
  passingExplanationShownHere,
  rememberPassingExplanationShown
} from './soloPassCopy.js'

// Seconds the explanation stays open when it opens by itself.
const FIRST_USE_COUNTDOWN_SECONDS = 5

// The tick box that lets registrations behind pass over this one, nested inside the 单人游玩
// option and shown only while that option is chosen, as on the terminal. It belongs to the
// play preference of one registration and is off unless the player turns it on. The host offers
// it only where the terminal has declared SET_ALLOWS_PASSING.
// With [fixedPair] it is the same choice of a fixed pair, worded for the pair and shown with the
// pair's state; the host offers that only where the terminal supports it.
// The first time a player profile turns either box on, the explanation opens by itself and can
// be closed after five seconds; the question mark opens the same explanation at any time. Both
// follow the tick box for two machines (PairedQueueTickBox.vue): the same control with the same
// label, on the right of the box's first line, as on the terminal.
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  fixedPair: { type: Boolean, default: false },
  // Whether the player profile in use has already been shown the explanation, as the server
  // reports it (passing_introduced), and what identifies that profile on this page.
  explanationShown: { type: Boolean, default: false },
  explanationKey: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue'])

const detailsOpen = ref(false)
const detailsCountdown = ref(0)
const label = computed(() => (
  props.fixedPair ? FIXED_PAIR_PASS_TICK_BOX_LABEL : SOLO_PASS_TICK_BOX_LABEL
))
const note = computed(() => (
  props.fixedPair ? FIXED_PAIR_PASS_TICK_BOX_NOTE : SOLO_PASS_TICK_BOX_NOTE
))
const detailsBlocks = computed(() => passingDetailsBlocks({ fixedPair: props.fixedPair }))

function change(event) {
  const turnedOn = event.target.checked
  emit('update:modelValue', turnedOn)
  if (
    turnedOn &&
    !props.explanationShown &&
    !passingExplanationShownHere(props.explanationKey)
  ) {
    rememberPassingExplanationShown(props.explanationKey)
    openDetails(FIRST_USE_COUNTDOWN_SECONDS)
  }
}

function openDetails(countdownSeconds) {
  detailsCountdown.value = countdownSeconds
  detailsOpen.value = true
}
</script>

<template>
  <div class="solo-pass-tick-box" :class="{ 'is-fixed-pair': fixedPair }">
    <div class="solo-pass-tick-box-row">
      <label :class="{ 'is-disabled': disabled }">
        <input type="checkbox" :checked="modelValue" :disabled="disabled" @change="change" />
        <span class="solo-pass-tick-box-text">
          <span>{{ label }}</span>
          <small>{{ note }}</small>
        </span>
      </label>
      <button type="button" class="solo-pass-tick-box-help" :aria-label="PAIRED_QUEUE_DETAILS_BUTTON_LABEL"
        :title="PAIRED_QUEUE_DETAILS_BUTTON_LABEL" @click="openDetails(0)">
        <CircleQuestionMark :size="18" aria-hidden="true" />
      </button>
    </div>
    <PairedQueueIntroduction v-if="detailsOpen" :title="PASSING_DETAILS_TITLE" :content-blocks="detailsBlocks"
      :countdown-seconds="detailsCountdown" @close="detailsOpen = false" />
  </div>
</template>

<style scoped>
.solo-pass-tick-box {
  min-width: 0; padding-top: 9px; text-align: left;
  border-top: 1px solid color-mix(in srgb, var(--queue-blue, #007aff) 14%, var(--queue-separator, #d2d2d7));
}
/* The pair's box stands on its own, not inside an option: framed as an option is. */
.solo-pass-tick-box.is-fixed-pair {
  padding: 10px 12px; border: 1px solid var(--queue-separator, #d2d2d7); border-radius: 9px;
  background: var(--queue-card, #fff);
}
.solo-pass-tick-box-row { display: flex; align-items: flex-start; gap: 6px; }
.solo-pass-tick-box label { display: flex; min-width: 0; flex: 1; align-items: flex-start; gap: 8px; cursor: pointer; }
.solo-pass-tick-box label.is-disabled { cursor: default; opacity: .55; }
.solo-pass-tick-box input { width: 17px; height: 17px; flex: none; margin: 1px 0 0; accent-color: var(--queue-blue, #007aff); }
.solo-pass-tick-box-text { display: grid; min-width: 0; gap: 4px; }
.solo-pass-tick-box-text > span { color: var(--queue-text, #1d1d1f); font-size: 11px; font-weight: 400; line-height: 1.55; }
.solo-pass-tick-box-text > small { color: var(--queue-secondary, #6e6e73); font-size: 10px; font-weight: 400; line-height: 1.55; }
/* The question mark of the tick box for two machines, its centre on the first line of the text. */
.solo-pass-tick-box-help {
  display: grid; width: 34px; height: 34px; flex: none; place-items: center; margin: -8px 0 0; padding: 0;
  border: 0; border-radius: 50%; color: var(--queue-blue, #007aff); background: transparent; cursor: pointer;
}
</style>
