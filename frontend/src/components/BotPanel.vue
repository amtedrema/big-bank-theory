<script setup lang="ts">
import { computed } from 'vue'

import type { LogEntry } from '@/stores/game'
import { useGameStore } from '@/stores/game'

const store = useGameStore()

const statusText = computed(() => {
  if (store.finished) {
    return 'Game over'
  }
  return store.autoplay ? 'Auto-play running' : 'Stopped'
})

/** Newest first, so the latest move is visible without scrolling the list. */
const entries = computed(() => [...store.log].reverse())

/** An id alone means little: a purchase is shown by its name; a solve names the ad on its own line. */
function moveText(entry: LogEntry): string {
  if (entry.move === 'BUY') {
    const name = store.shop.find((item) => item.id === entry.id)?.name ?? entry.id
    return `Buy ${name}`
  }
  return entry.move === 'SOLVE' ? 'Solve' : 'No move'
}

function toggle() {
  // Not awaited: the auto-play loop runs until it is stopped.
  if (store.autoplay) {
    store.stopAutoplay()
  } else if (!store.busy && !store.finished) {
    void store.startAutoplay()
  }
}
</script>

<template>
  <section class="panel bot-panel" aria-labelledby="bot-heading">
    <h2 id="bot-heading">Auto-play</h2>

    <div class="bot-controls">
      <!-- Stop is never blocked: the store is busy for most of auto-play. Auto-play is only
           aria-disabled (while busy or at game over), never disabled: a disabled button drops
           keyboard focus, for example when Stop turns it back into Auto-play during a move. -->
      <button
        type="button"
        :aria-disabled="store.finished || (!store.autoplay && store.busy)"
        @click="toggle"
      >
        {{ store.autoplay ? 'Stop' : 'Auto-play' }}
      </button>
      <p class="bot-status" role="status">{{ statusText }}</p>
      <p v-if="store.autoplay" class="hint">Stop auto-play to make your own moves.</p>
    </div>

    <!-- A plain labelled list, not a live region: the result line is the live region, so a move
         whose result text is new is announced there and the log is not read out a second time. -->
    <template v-if="entries.length > 0">
      <h3 id="log-heading">Decision log</h3>
      <div class="bot-log" role="region" aria-labelledby="log-heading" tabindex="0">
        <ul>
          <li v-for="(entry, index) in entries" :key="entries.length - index">
            <p class="message">Turn {{ entry.turn }}: {{ moveText(entry) }}</p>
            <p v-if="entry.message">{{ entry.message }}</p>
            <p>{{ entry.result }}</p>
            <p class="reason">{{ entry.reason }}</p>
          </li>
        </ul>
      </div>
    </template>
    <p v-else class="hint">No moves yet.</p>
  </section>
</template>
