<script setup lang="ts">
import { useGameStore } from '@/stores/game'

const store = useGameStore()

const missingGold = (cost: number) => cost - (store.game?.gold ?? 0)
</script>

<template>
  <section class="panel" aria-labelledby="shop-heading">
    <h2 id="shop-heading">Shop</h2>
    <p class="legend">
      Each purchase uses a turn. A healing potion adds one life. A 100 gold item adds one level and
      a 300 gold item two. Higher levels were seen with better-paying ads.
    </p>

    <p v-if="store.shop.length === 0">
      {{ store.busy ? 'Loading the shop...' : 'The shop is not available at the moment.' }}
    </p>
    <ul v-else class="cards">
      <li
        v-for="item in store.shop"
        :key="item.id"
        class="card shop-item"
        :class="{ recommended: item === store.recommendedItem }"
      >
        <p v-if="item === store.recommendedItem" class="badge">Recommended</p>
        <p class="message">{{ item.name }}</p>
        <p class="price">{{ item.cost }} gold</p>
        <p v-if="missingGold(item.cost) > 0" class="hint">
          Need {{ missingGold(item.cost) }} more gold
        </p>
        <button
          type="button"
          :aria-label="`Buy ${item.name}`"
          :disabled="store.busy || store.autoplay || store.finished || missingGold(item.cost) > 0"
          @click="store.buy(item.id)"
        >
          Buy
        </button>
      </li>
    </ul>
  </section>
</template>
