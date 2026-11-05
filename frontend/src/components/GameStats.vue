<script setup lang="ts">
import { useGameStore } from '@/stores/game'

const store = useGameStore()
</script>

<template>
  <section v-if="store.game" class="stats" aria-label="Game status">
    <dl>
      <div>
        <dt>Score</dt>
        <dd>{{ store.game.score }}</dd>
      </div>
      <div>
        <dt>Gold</dt>
        <dd>{{ store.game.gold }}</dd>
      </div>
      <div>
        <dt>Lives</dt>
        <dd>{{ store.game.lives }}</dd>
      </div>
      <div>
        <dt>Level</dt>
        <dd>{{ store.game.level }}</dd>
      </div>
      <div>
        <dt>Turn</dt>
        <dd>{{ store.game.turn }}</dd>
      </div>
    </dl>
  </section>
</template>
