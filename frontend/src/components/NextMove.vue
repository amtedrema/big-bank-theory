<script setup lang="ts">
import { computed } from 'vue'

import { isSolvable } from '@/api'
import { useGameStore } from '@/stores/game'

const store = useGameStore()

const move = computed(() => store.advice?.move)
const ad = computed(() => store.recommended?.ad)
const item = computed(() => store.recommendedItem)

const canSolve = computed(() => ad.value !== undefined && isSolvable(ad.value))
const canBuy = computed(() => item.value !== null && (store.game?.gold ?? 0) >= item.value.cost)

function act() {
  if (move.value?.type === 'SOLVE' && move.value.id !== null) {
    void store.solve(move.value.id)
  } else if (move.value?.type === 'BUY' && move.value.id !== null) {
    void store.buy(move.value.id)
  }
}
</script>

<template>
  <section v-if="store.advice" class="panel next-move" aria-labelledby="next-heading">
    <h2 id="next-heading">Next move</h2>

    <p v-if="move?.type === 'SOLVE' && ad" class="message">Solve: {{ ad.message }}</p>
    <p v-else-if="move?.type === 'BUY' && item" class="message">
      Buy: {{ item.name }} for {{ item.cost }} gold
    </p>
    <p v-else class="message">No move is recommended.</p>

    <p class="reason">{{ store.advice.reason }}</p>

    <p v-if="ad && !canSolve" class="note">This ad could not be decoded, so it cannot be solved.</p>
    <p v-if="item && !canBuy" class="hint">
      You need {{ item.cost - (store.game?.gold ?? 0) }} more gold for this.
    </p>

    <button
      v-if="ad || item"
      type="button"
      :aria-label="ad ? `Solve this ad: ${ad.message}` : undefined"
      :disabled="store.busy || store.autoplay || store.finished || !(ad ? canSolve : canBuy)"
      @click="act"
    >
      {{ ad ? 'Solve this ad' : `Buy ${item?.name}` }}
    </button>
  </section>
</template>
