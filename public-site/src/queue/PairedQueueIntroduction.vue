<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  PAIRED_QUEUE_DETAILS_CLOSE,
  PAIRED_QUEUE_DETAILS_TITLE,
  pairedQueueDetailsBlocks,
  pairedQueueDetailsCountdown
} from './pairedQueueCopy.js'

// The explanation of letting the system arrange a registration between the two machines of a
// pair. It opens by itself the first time a player turns the tick box on, and then waits
// [countdownSeconds] before it can be closed; behind the question mark it shows the same
// content and can be closed at once.
// The explanation of letting others pass (SoloPassTickBox.vue) works the same way and is shown
// by this component as well: it hands in its own [title] and [contentBlocks], and no machine.
const props = defineProps({
  pairedMachineName: { type: String, default: '' },
  countdownSeconds: { type: Number, default: 0 },
  title: { type: String, default: '' },
  contentBlocks: { type: Array, default: null }
})
const emit = defineEmits(['close'])

const heading = computed(() => props.title || PAIRED_QUEUE_DETAILS_TITLE)
const blocks = computed(() => props.contentBlocks || pairedQueueDetailsBlocks({
  pairedMachineName: props.pairedMachineName
}))
const remainingSeconds = ref(Math.max(0, Math.floor(props.countdownSeconds)))
let timer = null

function close() {
  if (remainingSeconds.value > 0) return
  emit('close')
}

// Esc belongs to this explanation while it is open: it waits for the countdown as a click
// outside does, and then closes only the explanation. Without this, the dialog or panel
// underneath took the key and closed itself, skipping the countdown and losing the tick
// (website review W9). Listening on the window in the capture phase comes before their
// handlers.
function handleKeydown(event) {
  if (event.key !== 'Escape') return
  event.preventDefault()
  event.stopImmediatePropagation()
  close()
}

onMounted(() => {
  window.addEventListener('keydown', handleKeydown, true)
  if (remainingSeconds.value <= 0) return
  timer = window.setInterval(() => {
    remainingSeconds.value = Math.max(0, remainingSeconds.value - 1)
    if (remainingSeconds.value === 0) {
      window.clearInterval(timer)
      timer = null
    }
  }, 1000)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleKeydown, true)
  if (timer !== null) window.clearInterval(timer)
})
</script>

<template>
  <Teleport to="body">
    <div class="paired-details-backdrop" @click.self="close">
      <section class="paired-details" role="dialog" aria-modal="true" :aria-label="heading">
        <h2>{{ heading }}</h2>
        <template v-for="(block, index) in blocks" :key="index">
          <p v-if="typeof block === 'string'">{{ block }}</p>
          <template v-else>
            <p>{{ block.lead }}</p>
            <ul>
              <li v-for="item in block.items" :key="item">{{ item }}</li>
            </ul>
          </template>
        </template>
        <button type="button" :disabled="remainingSeconds > 0" @click="close">{{ PAIRED_QUEUE_DETAILS_CLOSE }}</button>
        <small v-if="remainingSeconds > 0" aria-live="polite">{{ pairedQueueDetailsCountdown(remainingSeconds) }}</small>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.paired-details-backdrop { position: fixed; z-index: 12000; inset: 0; display: grid; place-items: center; padding: 20px; overflow-y: auto; background: rgba(0, 0, 0, .38); }
.paired-details { width: min(100%, 460px); padding: 22px 20px 18px; border-radius: 14px; color: #1d1d1f; background: #fff; box-shadow: 0 18px 48px rgba(0, 0, 0, .22); }
.paired-details h2 { margin: 0 0 10px; font-size: 18px; line-height: 1.35; }
.paired-details p { margin: 8px 0 0; color: #6e6e73; font-size: 13px; line-height: 1.65; }
.paired-details ul { margin: 4px 0 0; padding-left: 20px; color: #6e6e73; font-size: 13px; line-height: 1.65; }
.paired-details button { width: 100%; min-height: 46px; margin-top: 18px; border: 0; border-radius: 9px; color: #fff; background: #007aff; cursor: pointer; font-size: 14px; font-weight: 650; }
.paired-details button:disabled { cursor: default; opacity: .45; }
.paired-details small { display: block; margin-top: 8px; color: #8e8e93; font-size: 11px; line-height: 1.5; text-align: center; }
</style>
