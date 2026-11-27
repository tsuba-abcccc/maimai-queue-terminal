<script setup>
import { ChevronRight } from '@lucide/vue'
import { computed, ref } from 'vue'
import SoloPassTickBox from './SoloPassTickBox.vue'
import { FRIEND_PAIR_TEXT } from './queueActionAvailability.js'

// 与朋友共同游玩 with a registration that already waits at the same machine, in the steps and
// words of the terminal's flow (FriendPairFlowDialog): 选择朋友的登记, then the confirmation with
// the pair's passing tick box and the tick box that both players agree. The terminal shows how
// the order changes before it asks to confirm; the website cannot work that out, so the
// confirmation says the general rule, and the result is what the terminal answers. No consent
// of the friend is collected online: the confirmation stays grey until the box is ticked, as on
// the terminal.
// The account dialog and the registration detail of the status page both show this component.
const props = defineProps({
  // The rows of 选择朋友的登记 (friendPairCandidateRows).
  rows: { type: Array, required: true },
  disabled: { type: Boolean, default: false },
  // Whether the pair's passing tick box is offered: the terminal lets a fixed pair allow passing.
  passingOffered: { type: Boolean, default: false },
  // For the tick box's first explanation (SoloPassTickBox.vue).
  passingExplanationShown: { type: Boolean, default: false },
  passingExplanationKey: { type: String, default: '' }
})
// `confirm` carries { partnerRegistrationId, allowsPassing }; allowsPassing is null where the
// tick box was not offered.
const emit = defineEmits(['back', 'confirm'])

const selectedId = ref('')
const consent = ref(false)
const pairPassing = ref(false)
const reason = ref('')

// The chosen row as the page shows it now; it may have changed or gone since it was chosen.
const selectedRow = computed(() => props.rows.find((row) => row.id === selectedId.value) || null)
const selectionChanged = computed(() => !selectedRow.value || Boolean(selectedRow.value.reason))

function choose(row) {
  if (row.reason || props.disabled) return
  selectedId.value = row.id
  consent.value = false
  pairPassing.value = false
  reason.value = ''
}

function backToList() {
  selectedId.value = ''
  reason.value = ''
}

function confirm() {
  if (props.disabled || selectionChanged.value) return
  if (!consent.value) {
    reason.value = FRIEND_PAIR_TEXT.consentMissing
    return
  }
  reason.value = ''
  emit('confirm', {
    partnerRegistrationId: selectedRow.value.id,
    allowsPassing: props.passingOffered ? pairPassing.value : null
  })
}
</script>

<template>
  <div class="friend-pair-flow">
    <template v-if="!selectedId">
      <strong class="friend-pair-title">{{ FRIEND_PAIR_TEXT.selectTitle }}</strong>
      <p class="friend-pair-detail">{{ FRIEND_PAIR_TEXT.selectIntro }}</p>
      <div class="friend-pair-rows">
        <button v-for="row in rows" :key="row.id" type="button" class="friend-pair-row"
          :class="{ 'is-unavailable': row.reason }" :aria-disabled="row.reason ? 'true' : 'false'"
          :disabled="disabled" @click="choose(row)">
          <span class="friend-pair-row-main">
            <strong>{{ row.name }}</strong>
            <small>{{ row.description }}</small>
          </span>
          <ChevronRight v-if="!row.reason" :size="16" aria-hidden="true" />
        </button>
      </div>
      <div class="friend-pair-actions is-single">
        <button type="button" :disabled="disabled" @click="emit('back')">返回</button>
      </div>
    </template>
    <template v-else>
      <strong class="friend-pair-title">{{ FRIEND_PAIR_TEXT.confirmTitle }}</strong>
      <div v-if="selectedRow" class="friend-pair-row is-chosen" :class="{ 'is-unavailable': selectedRow.reason }">
        <span class="friend-pair-row-main">
          <strong>{{ selectedRow.name }}</strong>
          <small>{{ selectedRow.description }}</small>
        </span>
      </div>
      <p v-if="selectionChanged" class="friend-pair-changed" role="alert">{{ FRIEND_PAIR_TEXT.changed }}</p>
      <template v-else>
        <p class="friend-pair-detail">{{ FRIEND_PAIR_TEXT.rule }}</p>
        <p class="friend-pair-note">{{ FRIEND_PAIR_TEXT.note }}</p>
        <SoloPassTickBox v-if="passingOffered" v-model="pairPassing" fixed-pair :disabled="disabled"
          :explanation-shown="passingExplanationShown" :explanation-key="passingExplanationKey" />
        <label class="friend-pair-consent" :class="{ 'is-disabled': disabled }">
          <input v-model="consent" type="checkbox" :disabled="disabled" @change="reason = ''" />
          <span>{{ FRIEND_PAIR_TEXT.consent }}</span>
        </label>
      </template>
      <p v-if="reason" class="friend-pair-reason" role="alert">{{ reason }}</p>
      <div class="friend-pair-actions" :class="{ 'is-single': selectionChanged }">
        <button type="button" :disabled="disabled" @click="backToList">返回</button>
        <button v-if="!selectionChanged" type="button" class="primary" :class="{ 'is-unavailable': !consent }"
          :aria-disabled="consent ? 'false' : 'true'" :disabled="disabled" @click="confirm">
          {{ FRIEND_PAIR_TEXT.confirm }}
        </button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.friend-pair-flow { display: grid; min-width: 0; gap: 9px; }
.friend-pair-title { font-size: 12px; font-weight: 620; color: var(--queue-text, #1d1d1f); }
.friend-pair-detail { margin: 0; color: var(--queue-secondary, #6e6e73); font-size: 11px; line-height: 1.65; }
.friend-pair-note {
  margin: 0; padding: 9px 10px; border-left: 3px solid var(--queue-blue, #007aff);
  color: var(--queue-secondary, #6e6e73); background: var(--queue-soft-blue, #eaf3ff);
  font-size: 10px; line-height: 1.6;
}
.friend-pair-rows { display: grid; max-height: 280px; gap: 6px; overflow-y: auto; }
.friend-pair-row {
  display: flex; min-height: 52px; padding: 9px 11px; align-items: center; justify-content: space-between;
  gap: 12px; border: 1px solid var(--queue-separator, #d2d2d7); border-radius: 8px; text-align: left; font: inherit;
  color: var(--queue-text, #1d1d1f); background: var(--queue-card, #fff);
}
button.friend-pair-row { cursor: pointer; }
button.friend-pair-row:disabled, .friend-pair-row.is-unavailable { cursor: default; }
.friend-pair-row.is-chosen { border-color: var(--queue-blue, #007aff); background: var(--queue-soft-blue, #eaf3ff); }
.friend-pair-row.is-unavailable {
  border-color: var(--queue-separator, #d2d2d7); color: var(--queue-tertiary, #8e8e93);
  background: var(--queue-disabled, #e8e8ed);
}
.friend-pair-row-main { display: grid; min-width: 0; gap: 2px; }
.friend-pair-row-main strong { overflow-wrap: anywhere; font-size: 12px; font-weight: 620; line-height: 1.4; }
.friend-pair-row-main small { color: var(--queue-secondary, #6e6e73); font-size: 10px; font-weight: 400; line-height: 1.5; }
.friend-pair-row.is-unavailable small { color: var(--queue-tertiary, #8e8e93); }
.friend-pair-row > svg { flex: none; color: var(--queue-tertiary, #8e8e93); }
.friend-pair-consent {
  display: flex; padding: 10px 12px; align-items: center; gap: 9px; border-radius: 9px; cursor: pointer;
  color: var(--queue-text, #1d1d1f); background: var(--queue-position, var(--queue-soft, #f5f5f7));
  font-size: 11px; line-height: 1.55;
}
.friend-pair-consent.is-disabled { cursor: default; opacity: .55; }
.friend-pair-consent input { width: 17px; height: 17px; flex: none; margin: 0; padding: 0; accent-color: var(--queue-blue, #007aff); }
.friend-pair-changed, .friend-pair-reason { margin: 0; color: var(--queue-red, #c9342e); font-size: 10px; line-height: 1.55; }
.friend-pair-actions { display: grid; grid-template-columns: .8fr 1.2fr; gap: 6px; }
.friend-pair-actions.is-single { grid-template-columns: 1fr; }
.friend-pair-actions button {
  min-height: 42px; padding: 0 9px; border: 0; border-radius: 7px; cursor: pointer; font-size: 10px; font-weight: 620;
  color: var(--queue-text, #1d1d1f); background: var(--queue-position, var(--queue-soft, #f5f5f7));
}
.friend-pair-actions button.primary { color: #fff; background: var(--queue-blue, #007aff); }
.friend-pair-actions button.primary.is-unavailable, .friend-pair-actions button:disabled {
  color: var(--queue-tertiary, #8e8e93); background: var(--queue-disabled, #e8e8ed);
}
.friend-pair-actions button:disabled { cursor: default; }
</style>
