<script setup lang="ts">
import { computed } from 'vue'

import { isSolvable } from '@/api'
import { useGameStore } from '@/stores/game'

const store = useGameStore()

/** The recommended ad first, the others in the order the strategy rated them. */
const ads = computed(() => {
  const all = store.advice?.ads ?? []
  const best = store.recommended
  return best === null ? all : [best, ...all.filter((rated) => rated !== best)]
})

const percent = (value: number) => `${Math.round(value * 100)}%`
const turns = (n: number) => `${n} ${n === 1 ? 'turn' : 'turns'}`
</script>

<template>
  <section class="panel" aria-labelledby="ads-heading">
    <h2 id="ads-heading">Ads</h2>

    <template v-if="store.advice">
      <p class="legend">
        Expected value weighs the reward you might win against the life you might lose, so an ad
        that looks likely can still rate below zero when its reward is small.
      </p>

      <p v-if="ads.length === 0">There are no ads right now.</p>
      <ol v-else class="cards">
        <li
          v-for="rated in ads"
          :key="rated.ad.adId"
          class="card"
          :class="{ recommended: rated === store.recommended }"
        >
          <p v-if="rated === store.recommended" class="badge">Recommended</p>
          <p class="message">{{ rated.ad.message }}</p>
          <dl class="facts">
            <div>
              <dt>Reward</dt>
              <dd>{{ rated.ad.reward }} gold</dd>
            </div>
            <div>
              <dt>Difficulty</dt>
              <dd>{{ rated.label === 'UNKNOWN' ? 'Unknown' : rated.label }}</dd>
            </div>
            <div>
              <dt>Estimated chance</dt>
              <dd>{{ rated.label === 'UNKNOWN' ? 'Unknown' : percent(rated.probability) }}</dd>
            </div>
            <div>
              <dt>Expected value</dt>
              <dd>{{ rated.label === 'UNKNOWN' ? 'Unknown' : rated.expectedValue.toFixed(1) }}</dd>
            </div>
            <div>
              <dt>Expires in</dt>
              <dd>{{ turns(rated.ad.expiresIn) }}</dd>
            </div>
          </dl>
          <p v-if="rated !== store.recommended" class="reason">{{ rated.reason }}</p>
          <p v-if="!isSolvable(rated.ad)" class="note">
            This ad could not be decoded, so it cannot be solved.
          </p>
          <button
            type="button"
            :aria-label="`Solve: ${rated.ad.message}`"
            :disabled="store.busy || store.autoplay || store.finished || !isSolvable(rated.ad)"
            @click="store.solve(rated.ad.adId)"
          >
            Solve
          </button>
        </li>
      </ol>
    </template>

    <p v-else-if="store.busy">Loading ads...</p>
    <p v-else>Ads are not available at the moment.</p>
  </section>
</template>
