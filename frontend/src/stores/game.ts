import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import {
  ApiError,
  buyItem,
  getAdvice,
  getShop,
  isSolvable,
  solveAd,
  startGame,
  type Advice,
  type ErrorCode,
  type GameState,
  type Move,
  type ShopItem,
} from '@/api'

export interface LogEntry {
  /** The turn the move was made at. */
  turn: number
  move: Move['type']
  id: string | null
  reason: string
  result: string
  /** The text of the ad that was solved; missing for a purchase. */
  message?: string
}

interface StoreError {
  code: ErrorCode
  message: string
  /** True when retry() can help: the problem is on the way to the server, not in the request. */
  retryable: boolean
}

const STORAGE_KEY = 'bigbank.game'
const LOG_LIMIT = 50
const AUTOPLAY_DELAY_MS = 600
/** Steps in a row without the turn moving before auto-play gives up (same rule as the bot). */
const MAX_STUCK_STEPS = 2
const UNKNOWN_RESULT = 'We could not confirm that move. The numbers catch up over your next moves.'

const RETRYABLE: ErrorCode[] = [
  'NETWORK',
  'UNKNOWN',
  'UPSTREAM_UNAVAILABLE',
  'UPSTREAM_INVALID',
  'INTERNAL_ERROR',
]
/** The server answered that it did not do the move, so the result of the move is known. */
const REFUSED: ErrorCode[] = [
  'INVALID_INPUT',
  'REQUEST_REJECTED',
  'GAME_NOT_FOUND',
  'AD_UNAVAILABLE',
]
const GAME_OVER_TEXT = 'The final move may be missing from this score.'

const isNumber = (value: unknown): value is number =>
  typeof value === 'number' && Number.isFinite(value)
/** What the backend accepts for lives, gold, level, score and turn. */
const isCount = (value: unknown) => Number.isSafeInteger(value) && (value as number) >= 0

function isGameState(value: unknown): value is GameState {
  const g = value as Record<string, unknown> | null
  return (
    typeof g === 'object' &&
    g !== null &&
    typeof g.gameId === 'string' &&
    /^[A-Za-z0-9]{8}$/.test(g.gameId) &&
    [g.lives, g.gold, g.level, g.score, g.turn].every(isCount)
  )
}

function isLogEntry(value: unknown): value is LogEntry {
  const e = value as Record<string, unknown> | null
  return (
    typeof e === 'object' &&
    e !== null &&
    isNumber(e.turn) &&
    ['SOLVE', 'BUY', 'NONE'].includes(e.move as string) &&
    (e.id === null || typeof e.id === 'string') &&
    typeof e.reason === 'string' &&
    typeof e.result === 'string' &&
    (e.message === undefined || typeof e.message === 'string')
  )
}

function readStorage(): string | null {
  try {
    return sessionStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

function writeStorage(value: string | null) {
  try {
    if (value === null) {
      sessionStorage.removeItem(STORAGE_KEY)
    } else {
      sessionStorage.setItem(STORAGE_KEY, value)
    }
  } catch {
    // Storage can be blocked; the game still works, it just is not kept over a reload.
  }
}

export const useGameStore = defineStore('game', () => {
  const game = ref<GameState | null>(null)
  const advice = ref<Advice | null>(null)
  const shop = ref<ShopItem[]>([])
  const busy = ref(false)
  const error = ref<StoreError | null>(null)
  const lastResult = ref<string | null>(null)
  const log = ref<LogEntry[]>([])
  const autoplay = ref(false)

  // Not reactive: bookkeeping for one run() and for the current auto-play loop.
  let postFailed = false
  let loopId = 0

  /** Over when the lives are gone. A GAME_OVER answer sets the lives to 0, so this stays one rule. */
  const finished = computed(() => game.value !== null && game.value.lives <= 0)
  const recommended = computed(() => {
    const move = advice.value?.move
    if (move?.type !== 'SOLVE') {
      return null
    }
    return advice.value?.ads.find((rated) => rated.ad.adId === move.id) ?? null
  })
  const recommendedItem = computed(() => {
    const move = advice.value?.move
    if (move?.type !== 'BUY') {
      return null
    }
    return shop.value.find((item) => item.id === move.id) ?? null
  })

  function persist() {
    writeStorage(game.value === null ? null : JSON.stringify({ game: game.value, log: log.value }))
  }

  function addLog(entry: LogEntry) {
    log.value = [...log.value, entry].slice(-LOG_LIMIT)
  }

  function reset() {
    stopAutoplay()
    game.value = null
    advice.value = null
    shop.value = []
    log.value = []
    lastResult.value = null
  }

  async function handleError(e: unknown) {
    const apiError =
      e instanceof ApiError ? e : new ApiError('UNKNOWN', 'Something unexpected went wrong')
    const { code } = apiError
    if (code === 'AD_UNAVAILABLE' && postFailed) {
      // Normal: ads come and go. The server answered, so the result is known; reload the ads.
      postFailed = false
      lastResult.value = 'That ad is no longer available'
      try {
        await loadAdvice()
      } catch (reloadError) {
        await handleError(reloadError)
      }
      return
    }
    stopAutoplay()
    if (code === 'GAME_NOT_FOUND') {
      reset()
    } else if (code === 'GAME_OVER') {
      // Finished on the server while our lives may say otherwise (an answer was lost).
      if (game.value !== null) {
        game.value = { ...game.value, lives: 0 }
      }
      advice.value = null
      lastResult.value = GAME_OVER_TEXT
      return
    }
    if (postFailed && !REFUSED.includes(code)) {
      lastResult.value = UNKNOWN_RESULT
    }
    error.value = {
      code,
      message: apiError.message,
      retryable: game.value !== null && !finished.value && RETRYABLE.includes(code),
    }
  }

  /** The one wrapper for everything that talks to the backend. Ignored while another call runs. */
  async function run(action: () => Promise<void>) {
    if (busy.value) {
      return
    }
    busy.value = true
    error.value = null
    postFailed = false
    try {
      await action()
    } catch (e) {
      await handleError(e)
    } finally {
      busy.value = false
      persist()
    }
  }

  /**
   * A POST that changes the game. If it fails, the failure goes into the log too, and the POST is
   * never repeated: the next solve or buy answer corrects the state.
   */
  async function play<T>(
    type: Move['type'],
    id: string,
    reason: string,
    call: () => Promise<T>,
    message?: string,
  ) {
    try {
      return await call()
    } catch (e) {
      postFailed = true
      const apiError = e instanceof ApiError ? e : null
      const outcome = apiError && REFUSED.includes(apiError.code) ? 'Not done' : 'Result unknown'
      const result =
        apiError?.code === 'GAME_OVER'
          ? `Game over. ${GAME_OVER_TEXT}`
          : `${outcome}: ${apiError?.message ?? 'unexpected error'}`
      addLog({ turn: game.value?.turn ?? 0, move: type, id, reason, result, message })
      throw e
    }
  }

  /** Old advice never stays clickable after a failed reload. */
  async function loadAdvice() {
    if (game.value !== null) {
      try {
        advice.value = await getAdvice(game.value)
      } catch (e) {
        advice.value = null
        throw e
      }
    }
  }

  async function loadShop() {
    if (game.value !== null) {
      shop.value = await getShop(game.value.gameId)
    }
  }

  async function loadAll() {
    await loadShop()
    await loadAdvice()
  }

  function adviceFor(type: Move['type'], id: string): string {
    const move = advice.value?.move
    if (move?.type === type && move.id === id) {
      return advice.value?.reason ?? ''
    }
    return advice.value?.ads.find((rated) => rated.ad.adId === id)?.reason ?? ''
  }

  async function start() {
    await run(async () => {
      const first = await startGame()
      reset()
      game.value = first
      await loadAll()
    })
  }

  async function refreshAdvice() {
    await run(loadAdvice)
  }

  async function solve(adId: string) {
    const current = game.value
    if (current === null || finished.value) {
      return
    }
    const rated = advice.value?.ads.find((r) => r.ad.adId === adId)
    if (rated && !isSolvable(rated.ad)) {
      lastResult.value = 'This ad could not be decoded and cannot be solved'
      return
    }
    await run(async () => {
      const reason = adviceFor('SOLVE', adId)
      const message = rated?.ad.message
      const result = await play('SOLVE', adId, reason, () => solveAd(current.gameId, adId), message)
      // A solve answer has no level.
      const { lives, gold, score, turn } = result
      game.value = { ...current, lives, gold, score, turn }
      lastResult.value = `${result.success ? 'Success' : 'Failure'}: ${result.message}`
      addLog({
        turn: current.turn,
        move: 'SOLVE',
        id: adId,
        reason,
        result: lastResult.value,
        message,
      })
      await afterMove()
    })
  }

  async function buy(itemId: string) {
    const current = game.value
    if (current === null || finished.value) {
      return
    }
    await run(async () => {
      const reason = adviceFor('BUY', itemId)
      const result = await play('BUY', itemId, reason, () => buyItem(current.gameId, itemId))
      // A buy answer has no score.
      const { lives, gold, level, turn } = result
      game.value = { ...current, lives, gold, level, turn }
      const name = shop.value.find((item) => item.id === itemId)?.name ?? itemId
      lastResult.value = result.shoppingSuccess ? `Bought ${name}` : `Could not buy ${name}`
      addLog({ turn: current.turn, move: 'BUY', id: itemId, reason, result: lastResult.value })
      await afterMove()
    })
  }

  async function afterMove() {
    persist() // keep the received result even if the reload below is cut short
    if (finished.value) {
      advice.value = null
    } else {
      await loadAdvice()
    }
  }

  /** Loads shop and advice again; never repeats a solve or buy. A finished game loads nothing. */
  async function retry() {
    if (finished.value) {
      return
    }
    await run(loadAll)
  }

  /** Loads the saved game from sessionStorage. No network call; call retry() afterwards to get advice. */
  function restore(): boolean {
    const text = readStorage()
    if (text === null) {
      return false
    }
    let data: { game?: unknown; log?: unknown } | null = null
    try {
      data = JSON.parse(text)
    } catch {
      data = null
    }
    if (data && isGameState(data.game) && Array.isArray(data.log) && data.log.every(isLogEntry)) {
      game.value = data.game
      log.value = data.log.slice(-LOG_LIMIT)
      return true
    }
    writeStorage(null)
    return false
  }

  function stopAutoplay() {
    loopId++
    autoplay.value = false
  }

  function stopWithMessage(message: string) {
    lastResult.value = message
    stopAutoplay()
  }

  async function startAutoplay() {
    if (autoplay.value || busy.value || game.value === null || finished.value) {
      return
    }
    const myLoop = ++loopId
    autoplay.value = true
    let stuckSteps = 0
    const active = () => myLoop === loopId && !finished.value
    const wait = () => new Promise((resolve) => setTimeout(resolve, AUTOPLAY_DELAY_MS))

    // Also clears an old error, and never acts on advice from an earlier turn.
    await refreshAdvice()
    while (active()) {
      if (advice.value === null) {
        stopWithMessage('Auto-play stopped: no advice')
        break
      }
      // The strategy decides; this loop only carries out advice.move.
      const { move, reason } = advice.value
      const before = game.value?.turn ?? 0
      if (move.type === 'NONE' || move.id === null) {
        addLog({ turn: before, move: 'NONE', id: null, reason, result: 'No playable move' })
        stopWithMessage('Auto-play stopped: no playable move')
      } else if (
        move.type === 'SOLVE' &&
        advice.value.ads.some((r) => r.ad.adId === move.id && !isSolvable(r.ad))
      ) {
        stopWithMessage('Auto-play stopped: the recommended ad cannot be decoded')
      } else {
        await (move.type === 'SOLVE' ? solve(move.id) : buy(move.id))
        stuckSteps = (game.value?.turn ?? 0) > before ? 0 : stuckSteps + 1
        // A rejected ad can come back in the advice; do not loop on it.
        if (active() && stuckSteps >= MAX_STUCK_STEPS) {
          stopWithMessage(
            'Auto-play stopped: the turn did not advance (an ad may keep being rejected)',
          )
        }
      }
      if (active()) {
        await wait()
      }
    }
    if (myLoop === loopId) {
      autoplay.value = false
    }
  }

  return {
    game,
    advice,
    shop,
    busy,
    error,
    lastResult,
    log,
    autoplay,
    finished,
    recommended,
    recommendedItem,
    start,
    refreshAdvice,
    solve,
    buy,
    retry,
    restore,
    startAutoplay,
    stopAutoplay,
  }
})
