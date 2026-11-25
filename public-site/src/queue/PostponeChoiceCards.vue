<script setup>
// The three ways to postpone a registration as comparison cards, as on the terminal: each says
// when it fits, what happens when the registration is reached and how it ends. A card that does
// not apply is grey and shows why in place of its summary. The host renders the 取消 button.
defineProps({
  title: { type: String, required: true },
  intro: { type: String, required: true },
  cards: { type: Array, required: true },
  disabled: { type: Boolean, default: false }
})
const emit = defineEmits(['choose'])
</script>

<template>
  <div class="postpone-chooser">
    <strong class="postpone-title">{{ title }}</strong>
    <p class="postpone-intro">{{ intro }}</p>
    <div class="postpone-cards">
      <button v-for="card in cards" :key="card.mode" type="button" class="postpone-card"
        :class="{ 'is-unavailable': card.reason }" :aria-disabled="card.reason ? 'true' : 'false'"
        :disabled="disabled" @click="card.reason || emit('choose', card.mode)">
        <span class="postpone-card-heading">
          <span class="postpone-card-title">{{ card.title }}</span>
          <span class="postpone-card-arrow" aria-hidden="true">›</span>
        </span>
        <span class="postpone-card-summary">{{ card.reason || card.summary }}</span>
        <span v-for="[label, text] in card.facts" :key="label" class="postpone-card-fact">
          <span class="postpone-card-label">{{ label }}</span>
          <span>{{ text }}</span>
        </span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.postpone-chooser { display: grid; gap: 8px; margin-top: 10px; container-type: inline-size; }
.postpone-title { font-size: 13px; font-weight: 620; color: var(--queue-text, #1d1d1f); }
.postpone-intro { margin: 0; color: var(--queue-secondary, #6e6e73); font-size: 11px; line-height: 1.6; }
/* One column in a narrow dialog; three side by side, as on the terminal, where there is room. */
.postpone-cards { display: grid; gap: 8px; grid-template-columns: 1fr; }
@container (min-width: 600px) {
  .postpone-cards { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
.postpone-card {
  display: grid; gap: 4px; align-content: start; padding: 11px 12px; text-align: left;
  border: 1px solid var(--queue-separator, #d2d2d7); border-radius: 10px;
  color: var(--queue-text, #1d1d1f); background: var(--queue-card, #fff); cursor: pointer; font: inherit;
}
.postpone-card-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.postpone-card-title { font-size: 13px; font-weight: 620; }
.postpone-card-arrow { color: var(--queue-tertiary, #8e8e93); font-size: 16px; line-height: 1; }
.postpone-card-summary { margin-bottom: 4px; color: var(--queue-orange, #b85c00); font-size: 11px; font-weight: 600; line-height: 1.5; }
.postpone-card-fact { display: grid; gap: 1px; color: var(--queue-secondary, #6e6e73); font-size: 10px; line-height: 1.55; }
.postpone-card-label { color: var(--queue-tertiary, #8e8e93); font-size: 9.5px; font-weight: 600; }
.postpone-card.is-unavailable { cursor: default; background: var(--queue-disabled, #e8e8ed); }
.postpone-card.is-unavailable .postpone-card-title,
.postpone-card.is-unavailable .postpone-card-summary,
.postpone-card.is-unavailable .postpone-card-fact,
.postpone-card.is-unavailable .postpone-card-arrow { color: var(--queue-tertiary, #8e8e93); }
.postpone-card:disabled { cursor: default; }
</style>
