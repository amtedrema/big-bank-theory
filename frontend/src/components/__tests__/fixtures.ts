import { createPinia, setActivePinia } from 'pinia'
import { vi } from 'vitest'

import type { Ad, Advice, GameState, Move, ShopItem } from '@/api'
import { useGameStore } from '@/stores/game'

export const GAME: GameState = {
  gameId: 'abcd1234',
  lives: 3,
  gold: 0,
  level: 0,
  score: 0,
  turn: 0,
}

export const ad = (adId: string, over: Partial<Ad> = {}): Ad => ({
  adId,
  message: `Message ${adId}`,
  reward: 20,
  expiresIn: 5,
  probability: 'Piece of cake',
  encrypted: null,
  ...over,
})

export function adviceOf(move: Move, ads: Ad[], reason = 'Because'): Advice {
  return {
    ads: ads.map((a) => ({
      ad: a,
      label: 'Piece of cake',
      probability: 0.8,
      expectedValue: 16,
      reason: `Rated ${a.adId}`,
    })),
    move,
    reason,
  }
}

export const SHOP: ShopItem[] = [
  { id: 'hpot', name: 'Healing potion', cost: 50 },
  { id: 'cs', name: 'Claw sharpening', cost: 100 },
]

/** A fresh store with a game in progress; tests then set whatever else they need. */
export function playingStore(game: Partial<GameState> = {}) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useGameStore()
  store.game = { ...GAME, ...game }
  return { pinia, store }
}

/** Spies on the store actions the components call; they are also replaced unless `replace` is false. */
export function spyOnActions(store: ReturnType<typeof useGameStore>, replace = true) {
  const actions = {
    solve: vi.spyOn(store, 'solve'),
    buy: vi.spyOn(store, 'buy'),
    retry: vi.spyOn(store, 'retry'),
    start: vi.spyOn(store, 'start'),
    startAutoplay: vi.spyOn(store, 'startAutoplay'),
    stopAutoplay: vi.spyOn(store, 'stopAutoplay'),
  }
  if (replace) {
    Object.values(actions).forEach((spy) => spy.mockResolvedValue(undefined as never))
  }
  return actions
}
