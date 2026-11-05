<script setup lang="ts">
import { computed, nextTick, onMounted, useTemplateRef, watch } from 'vue'

import AdList from '@/components/AdList.vue'
import BotPanel from '@/components/BotPanel.vue'
import GameStats from '@/components/GameStats.vue'
import NextMove from '@/components/NextMove.vue'
import ShopPanel from '@/components/ShopPanel.vue'
import { useGameStore } from '@/stores/game'

const store = useGameStore()
const status = useTemplateRef<HTMLElement>('status')
const banner = useTemplateRef<HTMLElement>('banner')

/** What the live region says. At game over the final score comes first, so it is always announced. */
const statusText = computed(() => {
  const over = store.finished && store.game ? `Game over. Final score: ${store.game.score}.` : ''
  return [over, store.lastResult].filter(Boolean).join(' ')
})

// A move disables or removes the button that was pressed, and the browser then drops keyboard
// focus to <body>. When that happens, put it back on the error, or else on the result line.
let hadButtonFocus = false
watch(
  () => store.busy,
  async (busy) => {
    if (busy) {
      hadButtonFocus = document.activeElement instanceof HTMLButtonElement
      return
    }
    await nextTick()
    // An error comes first: it can be far above the sticky bar. Some browsers do not focus a clicked
    // button, so an error is revealed even when there is no focus to move.
    const moveFocus = hadButtonFocus && document.activeElement === document.body
    const target = banner.value ?? (moveFocus ? status.value : null)
    if (moveFocus) {
      // Focus must not scroll the page; only an error above the window is scrolled into view.
      target?.focus({ preventScroll: true })
    }
    if (target && target.getBoundingClientRect().top < 0) {
      target.scrollIntoView()
    }
  },
)

onMounted(() => {
  // restore() makes no network call; retry() then loads shop and advice (nothing for a finished game).
  if (store.restore()) {
    void store.retry()
  }
})
</script>

<template>
  <header>
    <h1>Dragons of Mugloar</h1>
  </header>

  <main :aria-busy="store.busy">
    <div v-if="store.error" ref="banner" class="banner" role="alert" tabindex="-1">
      <p>{{ store.error.message }}</p>
      <button
        v-if="store.error.retryable"
        type="button"
        :disabled="store.busy"
        @click="store.retry()"
      >
        Retry
      </button>
      <button
        v-else-if="store.game && !store.finished"
        type="button"
        :disabled="store.busy"
        @click="store.start()"
      >
        New game
      </button>
    </div>

    <section v-if="!store.game" class="start" aria-labelledby="start-heading">
      <h2 id="start-heading">Ready to fly?</h2>
      <p>Start a game, read the ads, and choose which one to solve. Gold buys help in the shop.</p>
      <p>
        Solve ads to earn gold and score. A failed ad costs a life, and the game ends at zero lives.
        The goal is 1000 points.
      </p>
      <button type="button" :disabled="store.busy" @click="store.start()">Start game</button>
    </section>

    <template v-else>
      <div class="status-bar">
        <GameStats />
        <p ref="status" class="result" tabindex="-1" role="status" aria-live="polite">
          <span v-if="store.busy && !store.autoplay" class="working" aria-hidden="true"
            >Working... </span
          >{{ statusText }}
        </p>
      </div>

      <section v-if="store.finished" class="panel" aria-labelledby="over-heading">
        <h2 id="over-heading">Game over</h2>
        <p>
          Final score: <strong>{{ store.game.score }}</strong>
        </p>
        <button type="button" :disabled="store.busy" @click="store.start()">New game</button>
      </section>

      <!-- Before NextMove, which changes height on every step: Stop must not move under the finger. -->
      <BotPanel />

      <template v-if="!store.finished">
        <NextMove />
        <div class="layout">
          <AdList />
          <ShopPanel />
        </div>
      </template>
    </template>
  </main>
</template>
