import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import type { Advice, Ad, BuyResult, Move, SolveResult } from '@/api'
import { ad, GAME } from '@/components/__tests__/fixtures'
import { useGameStore } from '../game'

function advice(move: Move, ads: Ad[] = [ad('ad000001')]): Advice {
  return {
    ads: ads.map((a) => ({
      ad: a,
      label: 'Piece of cake',
      probability: 0.8,
      expectedValue: 16,
      reason: `rated ${a.adId}`,
    })),
    move,
    reason: 'because',
  }
}

const solveMove: Move = { type: 'SOLVE', id: 'ad000001' }
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const problem = (status: number, code: string) => json({ status, detail: 'Text', code }, status)

const solveResult = (over: Partial<SolveResult>): SolveResult => ({
  success: true,
  lives: 3,
  gold: 20,
  score: 20,
  turn: 1,
  message: 'You won',
  ...over,
})
const buyResult = (over: Partial<BuyResult>): BuyResult => ({
  shoppingSuccess: true,
  gold: 0,
  lives: 3,
  level: 1,
  turn: 1,
  ...over,
})

type Handler = () => Response | Error | Promise<Response>
type Routes = Partial<Record<'start' | 'advice' | 'shop' | 'solve' | 'buy', Handler>>

function routeOf(path: string): keyof Routes {
  if (path.endsWith('/advice')) return 'advice'
  if (path.endsWith('/shop')) return 'shop'
  if (path.endsWith('/solve')) return 'solve'
  if (path.endsWith('/buy')) return 'buy'
  return 'start'
}

/** Stubs fetch with a fake server. Returns the list of calls as "METHOD path". */
function stubServer(routes: Routes = {}) {
  const calls: string[] = []
  const defaults: Required<Routes> = {
    start: () => json(GAME),
    advice: () => json(advice(solveMove)),
    shop: () => json([{ id: 'hpot', name: 'Healing potion', cost: 50 }]),
    solve: () => json(solveResult({})),
    buy: () => json(buyResult({})),
  }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init?: RequestInit) => {
      calls.push(`${init?.method} ${path}`)
      const key = routeOf(path)
      const result = await (routes[key] ?? defaults[key])()
      if (result instanceof Error) {
        throw result
      }
      return result
    }),
  )
  return calls
}

const posts = (calls: string[]) => calls.filter((c) => /solve$|buy$/.test(c))

async function started(routes: Routes = {}) {
  const calls = stubServer(routes)
  const store = useGameStore()
  await store.start()
  calls.length = 0
  return { store, calls }
}

beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('start', () => {
  it('starts, loads shop and advice, and persists', async () => {
    const calls = stubServer()
    const store = useGameStore()
    await store.start()
    expect(calls).toEqual([
      'POST /api/games',
      'GET /api/games/abcd1234/shop',
      'POST /api/games/abcd1234/advice',
    ])
    expect(store.game).toEqual(GAME)
    expect(store.shop).toHaveLength(1)
    expect(store.advice).not.toBeNull()
    expect(JSON.parse(sessionStorage.getItem('bigbank.game') ?? '{}').game).toEqual(GAME)
  })

  it('keeps the old game when starting fails', async () => {
    const { store } = await started()
    stubServer({ start: () => new TypeError('offline') })
    await store.start()
    expect(store.game).toEqual(GAME)
    expect(store.error?.code).toBe('NETWORK')
  })
})

describe('solve', () => {
  it('merges the answer, keeps the level, logs, and reloads advice', async () => {
    const { store, calls } = await started({ solve: () => json(solveResult({ score: 50 })) })
    store.game = { ...GAME, level: 2 }
    await store.solve('ad000001')
    expect(store.game).toEqual({ ...GAME, level: 2, gold: 20, score: 50, turn: 1 })
    expect(store.log).toEqual([
      {
        turn: 0,
        move: 'SOLVE',
        id: 'ad000001',
        reason: 'because',
        result: 'Success: You won',
        message: 'Message ad000001',
      },
    ])
    expect(calls).toEqual([
      'POST /api/games/abcd1234/ads/ad000001/solve',
      'POST /api/games/abcd1234/advice',
    ])
  })

  it('treats success false as a normal answer: merged and logged', async () => {
    const { store } = await started({
      solve: () => json(solveResult({ success: false, lives: 2, message: 'You lost', score: 0 })),
    })
    await store.solve('ad000001')
    expect(store.error).toBeNull()
    expect(store.game?.lives).toBe(2)
    expect(store.game?.turn).toBe(1)
    expect(store.log[0]?.result).toBe('Failure: You lost')
  })

  it('does not send an ad that could not be decoded', async () => {
    const { store, calls } = await started({
      advice: () => json(advice(solveMove, [ad('ad000001'), ad('QUJDRA==', { encrypted: '1' })])),
    })
    await store.refreshAdvice()
    calls.length = 0
    await store.solve('QUJDRA==')
    expect(calls).toEqual([])
    expect(store.lastResult).toContain('cannot be solved')
  })

  it('sends an ad whose label is UNKNOWN', async () => {
    const unknownLabel = advice(solveMove)
    unknownLabel.ads[0]!.label = 'UNKNOWN'
    const { store, calls } = await started({ advice: () => json(unknownLabel) })
    await store.solve('ad000001')
    expect(posts(calls)).toHaveLength(1)
  })

  it('reloads advice on AD_UNAVAILABLE and shows no error', async () => {
    const { store, calls } = await started({ solve: () => problem(409, 'AD_UNAVAILABLE') })
    await store.solve('ad000001')
    expect(store.error).toBeNull()
    expect(store.lastResult).toContain('no longer available')
    expect(calls).toEqual([
      'POST /api/games/abcd1234/ads/ad000001/solve',
      'POST /api/games/abcd1234/advice',
    ])
  })

  it('resets to the start screen on GAME_NOT_FOUND', async () => {
    const { store } = await started({ solve: () => problem(404, 'GAME_NOT_FOUND') })
    await store.solve('ad000001')
    expect(store.game).toBeNull()
    expect(store.advice).toBeNull()
    expect(store.shop).toEqual([])
    expect(store.error).toMatchObject({ code: 'GAME_NOT_FOUND', retryable: false })
    expect(store.lastResult).toBeNull()
    expect(sessionStorage.getItem('bigbank.game')).toBeNull()
  })

  it('ends the game on GAME_OVER even when the local lives are not 0', async () => {
    const { store, calls } = await started({ solve: () => problem(410, 'GAME_OVER') })
    store.game = { ...GAME, score: 345 }
    await store.solve('ad000001')
    expect(store.finished).toBe(true)
    expect(store.game?.score).toBe(345)
    expect(store.error).toBeNull()
    expect(store.lastResult).toBe('The final move may be missing from this score.')
    expect(store.log).toHaveLength(1)
    expect(store.log[0]?.result).toBe('Game over. The final move may be missing from this score.')
    expect(store.advice).toBeNull()
    expect(calls).toEqual(['POST /api/games/abcd1234/ads/ad000001/solve'])

    // After a reload the finished game comes back finished and makes no further moves.
    setActivePinia(createPinia())
    const reloaded = useGameStore()
    calls.length = 0
    expect(reloaded.restore()).toBe(true)
    expect(reloaded.finished).toBe(true)
    expect(reloaded.game?.score).toBe(345)
    await reloaded.retry()
    expect(calls).toEqual([])
    expect(reloaded.log).toHaveLength(1)
    await reloaded.solve('ad000001')
    expect(posts(calls)).toEqual([])
  })

  it('does not load advice when the solve ends the game', async () => {
    const { store, calls } = await started({
      solve: () => json(solveResult({ lives: 0, score: 80 })),
    })
    await store.solve('ad000001')
    expect(store.finished).toBe(true)
    expect(store.advice).toBeNull()
    expect(calls).toEqual(['POST /api/games/abcd1234/ads/ad000001/solve'])
  })

  it('shows a retryable error and says the result is unknown when the answer is lost', async () => {
    const { store, calls } = await started({ solve: () => new TypeError('offline') })
    await store.solve('ad000001')
    expect(store.error).toMatchObject({ code: 'NETWORK', retryable: true })
    expect(store.lastResult).toBe(
      'We could not confirm that move. The numbers catch up over your next moves.',
    )
    expect(store.game).toEqual(GAME)
    expect(store.log).toHaveLength(1)
    expect(store.log[0]).toMatchObject({ move: 'SOLVE', id: 'ad000001' })
    expect(store.log[0]?.result).toContain('Result unknown')

    calls.length = 0
    await store.retry()
    expect(posts(calls)).toEqual([])
    expect(calls).toEqual(['GET /api/games/abcd1234/shop', 'POST /api/games/abcd1234/advice'])
    expect(store.error).toBeNull()
  })

  it('keeps a successful solve when only the advice reload fails', async () => {
    const { store } = await started({ advice: () => problem(503, 'UPSTREAM_UNAVAILABLE') })
    await store.solve('ad000001')
    expect(store.game?.turn).toBe(1)
    expect(store.error?.code).toBe('UPSTREAM_UNAVAILABLE')
    expect(store.lastResult).toBe('Success: You won')
    expect(store.advice).toBeNull() // old ads must not stay clickable
  })

  it('ignores a second call while busy: exactly one fetch', async () => {
    const { store, calls } = await started()
    await Promise.all([store.solve('ad000001'), store.solve('ad000001')])
    expect(posts(calls)).toHaveLength(1)
  })
})

describe('buy', () => {
  it('merges the answer, keeps the score, and logs the purchase', async () => {
    const { store } = await started()
    store.game = { ...GAME, gold: 60, score: 99 }
    await store.buy('hpot')
    expect(store.game).toEqual({ ...GAME, gold: 0, score: 99, level: 1, turn: 1 })
    expect(store.log[0]).toMatchObject({ move: 'BUY', id: 'hpot', result: 'Bought Healing potion' })
    expect(store.log[0]?.message).toBeUndefined()
  })

  it('merges a failed buy but does not log it as a purchase', async () => {
    const { store } = await started({
      buy: () => json(buyResult({ shoppingSuccess: false, gold: 60, turn: 1 })),
    })
    await store.buy('hpot')
    expect(store.error).toBeNull()
    expect(store.game?.turn).toBe(1)
    expect(store.log[0]?.result).toBe('Could not buy Healing potion')
    expect(store.log[0]?.result).not.toContain('Bought')
  })
})

describe('persistence', () => {
  const entry = { turn: 1, move: 'SOLVE', id: 'ad000001', reason: 'r', result: 'x' }
  const save = (value: unknown) => sessionStorage.setItem('bigbank.game', JSON.stringify(value))

  it('restores a valid game without any fetch', () => {
    const calls = stubServer()
    save({ game: GAME, log: [] })
    const store = useGameStore()
    expect(store.restore()).toBe(true)
    expect(store.game).toEqual(GAME)
    expect(calls).toEqual([])
  })

  it('restores a log saved without the ad message, and one that has it', () => {
    const old = { turn: 1, move: 'SOLVE', id: 'ad000001', reason: 'r', result: 'Success: x' }
    save({ game: GAME, log: [old, { ...old, turn: 2, message: 'Help a duck' }] })
    const store = useGameStore()
    expect(store.restore()).toBe(true)
    expect(store.log.map((e) => e.message)).toEqual([undefined, 'Help a duck'])
  })

  it('drops corrupt JSON', () => {
    sessionStorage.setItem('bigbank.game', '{not json')
    const store = useGameStore()
    expect(store.restore()).toBe(false)
    expect(store.game).toBeNull()
    expect(sessionStorage.getItem('bigbank.game')).toBeNull()
  })

  it.each([
    ['a missing game id', { game: { ...GAME, gameId: '' }, log: [] }],
    ['a number that is null', { game: { ...GAME, score: null }, log: [] }],
    ['a text number', { game: { ...GAME, gold: '5' }, log: [] }],
    ['no log', { game: GAME }],
    ['a bad log entry', { game: GAME, log: [{ turn: 1 }] }],
    [
      'a log message that is not text',
      {
        game: GAME,
        log: [{ turn: 1, move: 'SOLVE', id: 'a', reason: 'r', result: 'x', message: 5 }],
      },
    ],
    ['null', null],
    ['a game id of the wrong shape', { game: { ...GAME, gameId: 'short' }, log: [] }],
    ['negative lives', { game: { ...GAME, lives: -1 }, log: [] }],
    ['a fraction', { game: { ...GAME, level: 1.5 }, log: [] }],
    ['a number too large to count', { game: { ...GAME, score: 1e300 }, log: [] }],
    ['a log id that is a number', { game: GAME, log: [{ ...entry, id: 5 }] }],
    ['a log move that is not known', { game: GAME, log: [{ ...entry, move: 'JUMP' }] }],
    ['a log turn that is text', { game: GAME, log: [{ ...entry, turn: '1' }] }],
  ])('drops a wrong shape: %s', (_name, value) => {
    save(value)
    const store = useGameStore()
    expect(store.restore()).toBe(false)
    expect(store.game).toBeNull()
    expect(sessionStorage.getItem('bigbank.game')).toBeNull()
  })
})

describe('autoplay', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  /** Runs the loop to its end; the delay timers are advanced as they come. */
  async function runLoop(store: ReturnType<typeof useGameStore>) {
    const loop = store.startAutoplay()
    await vi.advanceTimersByTimeAsync(20_000)
    await loop
  }

  it('plays advice.move and stops by itself when the lives run out', async () => {
    let turn = 0
    const { store, calls } = await started({
      solve: () => {
        turn++
        return json(solveResult({ turn, lives: turn >= 3 ? 0 : 3, score: turn * 10 }))
      },
    })
    await runLoop(store)
    expect(store.finished).toBe(true)
    expect(store.autoplay).toBe(false)
    expect(store.lastResult).not.toContain('Auto-play stopped') // it ended by itself
    expect(posts(calls)).toHaveLength(3)
    expect(store.log).toHaveLength(3)
  })

  it('executes a BUY move through buy', async () => {
    let turn = 0
    const { store, calls } = await started({
      advice: () => json(advice({ type: 'BUY', id: 'hpot' })),
      buy: () => json(buyResult({ turn: ++turn, lives: turn >= 2 ? 0 : 3 })),
    })
    await runLoop(store)
    expect(posts(calls)).toEqual([
      'POST /api/games/abcd1234/shop/hpot/buy',
      'POST /api/games/abcd1234/shop/hpot/buy',
    ])
  })

  it('stops when stopAutoplay is called during the delay: no further moves', async () => {
    const { store } = await started()
    let turn = 0
    const calls = stubServer({ solve: () => json(solveResult({ turn: ++turn })) })
    const loop = store.startAutoplay()
    await vi.advanceTimersByTimeAsync(0)
    const before = calls.length
    store.stopAutoplay()
    expect(store.autoplay).toBe(false)
    await vi.advanceTimersByTimeAsync(5_000)
    await loop
    expect(calls.length).toBe(before)
    expect(turn).toBeLessThanOrEqual(1)
  })

  it('a stopped loop does not move again after a new loop has started', async () => {
    let solves = 0
    const { store } = await started({
      solve: () => json(solveResult({ turn: ++solves, score: solves })),
    })
    const first = store.startAutoplay()
    await vi.advanceTimersByTimeAsync(0)
    expect(solves).toBe(1)
    await vi.advanceTimersByTimeAsync(300) // the first loop would wake at 600
    store.stopAutoplay()
    const second = store.startAutoplay() // steps now, then waits until 900
    await vi.advanceTimersByTimeAsync(0)
    expect(solves).toBe(2)
    await vi.advanceTimersByTimeAsync(400) // 700: the first loop has woken if it is still alive
    expect(solves).toBe(2)
    await vi.advanceTimersByTimeAsync(300) // 1000: the second loop made its step
    expect(solves).toBe(3)
    expect(store.autoplay).toBe(true)
    store.stopAutoplay()
    await vi.advanceTimersByTimeAsync(1000)
    await Promise.all([first, second])
  })

  it.each([
    ['UPSTREAM_UNAVAILABLE', () => problem(503, 'UPSTREAM_UNAVAILABLE')],
    ['NETWORK', () => new TypeError('offline')],
  ] as [string, Handler][])('stops on %s and does not repeat the POST', async (code, answer) => {
    const { store, calls } = await started({ solve: answer })
    await runLoop(store)
    expect(store.error?.code).toBe(code)
    expect(store.autoplay).toBe(false)
    expect(posts(calls)).toHaveLength(1)
  })

  it('stops with a message when the same ad is rejected twice', async () => {
    const { store, calls } = await started({ solve: () => problem(409, 'AD_UNAVAILABLE') })
    await runLoop(store)
    expect(store.autoplay).toBe(false)
    expect(store.error).toBeNull()
    expect(posts(calls)).toHaveLength(2)
    expect(store.lastResult).toContain('Auto-play stopped')
  })

  it('stops with a message when the turn does not advance over two steps', async () => {
    const { store, calls } = await started({ solve: () => json(solveResult({ turn: 0 })) })
    await runLoop(store)
    expect(store.autoplay).toBe(false)
    expect(posts(calls)).toHaveLength(2)
    expect(store.lastResult).toContain('turn did not advance')
  })

  it('stops with a log entry on a NONE move', async () => {
    const { store, calls } = await started({
      advice: () => json(advice({ type: 'NONE', id: null })),
    })
    await runLoop(store)
    expect(posts(calls)).toEqual([])
    expect(store.autoplay).toBe(false)
    expect(store.log[0]).toMatchObject({ move: 'NONE', result: 'No playable move' })
  })

  it('stops when the recommended ad could not be decoded', async () => {
    const { store, calls } = await started({
      advice: () => json(advice(solveMove, [ad('ad000001', { encrypted: '9' })])),
    })
    await runLoop(store)
    expect(posts(calls)).toEqual([])
    expect(store.lastResult).toContain('cannot be decoded')
  })

  it('does not start on a finished game', async () => {
    const { store, calls } = await started()
    store.game = { ...GAME, lives: 0 }
    await store.startAutoplay()
    expect(store.autoplay).toBe(false)
    expect(calls).toEqual([])
  })

  it('starts counting stuck steps again when the turn moves', async () => {
    let n = 0
    const { store } = await started({
      solve: () => {
        n++
        return n % 2 === 1
          ? problem(409, 'AD_UNAVAILABLE')
          : json(solveResult({ turn: n, lives: n >= 8 ? 0 : 3 }))
      },
    })
    await runLoop(store)
    expect(store.finished).toBe(true)
    expect(n).toBe(8)
  })

  it('starts from an error: reads fresh advice first and clears the error', async () => {
    let down = false
    const { store, calls } = await started({
      advice: () => (down ? problem(503, 'UPSTREAM_UNAVAILABLE') : json(advice(solveMove))),
      solve: () => json(solveResult({ lives: 0 })),
    })
    down = true
    await store.refreshAdvice()
    expect(store.error).not.toBeNull()
    down = false
    calls.length = 0
    await runLoop(store)
    expect(store.error).toBeNull()
    expect(calls[0]).toBe('POST /api/games/abcd1234/advice')
    expect(store.finished).toBe(true)
  })

  it('stops at once when the advice is null', async () => {
    const { store, calls } = await started({ advice: () => new Response('null') })
    await runLoop(store)
    expect(store.autoplay).toBe(false)
    expect(store.lastResult).toContain('Auto-play stopped')
    expect(calls).toHaveLength(1)
  })

  it('does not start while a move is running', async () => {
    const { store, calls } = await started()
    const click = store.solve('ad000001')
    await store.startAutoplay()
    expect(store.autoplay).toBe(false)
    await click
    expect(posts(calls)).toHaveLength(1)
  })

  it('does not start without a game', async () => {
    const calls = stubServer()
    const store = useGameStore()
    await store.startAutoplay()
    expect(store.autoplay).toBe(false)
    expect(store.lastResult).toBeNull()
    expect(calls).toEqual([])
  })

  it('a new game stops a running loop', async () => {
    let n = 0
    const { store } = await started({ solve: () => json(solveResult({ turn: ++n })) })
    const loop = store.startAutoplay()
    await vi.advanceTimersByTimeAsync(0) // one step done, the loop waits
    await store.start()
    expect(store.autoplay).toBe(false)
    await vi.advanceTimersByTimeAsync(5_000)
    await loop
    expect(n).toBe(1)
  })

  it('does not start a second loop while one waits', async () => {
    let n = 0
    const { store } = await started({ solve: () => json(solveResult({ turn: ++n })) })
    const first = store.startAutoplay()
    await vi.advanceTimersByTimeAsync(0)
    await store.startAutoplay() // ignored: no extra step now
    expect(n).toBe(1)
    store.stopAutoplay()
    await vi.advanceTimersByTimeAsync(5_000)
    await first
  })
})

describe('error codes', () => {
  const html = () => new Response('<html>HTTP Status 400</html>', { status: 400 })

  // code, answer, retryable, whether the result of the move is unknown
  it.each([
    ['INVALID_INPUT', () => problem(400, 'INVALID_INPUT'), false, false],
    ['REQUEST_REJECTED', () => problem(405, 'REQUEST_REJECTED'), false, false],
    ['UPSTREAM_UNAVAILABLE', () => problem(503, 'UPSTREAM_UNAVAILABLE'), true, true],
    ['UPSTREAM_INVALID', () => problem(502, 'UPSTREAM_INVALID'), true, true],
    ['INTERNAL_ERROR', () => problem(500, 'INTERNAL_ERROR'), true, true],
    ['NETWORK', () => new TypeError('offline'), true, true],
    ['UNKNOWN', html, true, true],
  ] as [string, Handler, boolean, boolean][])(
    '%s on a solve',
    async (code, answer, retryable, unknown) => {
      const { store, calls } = await started({ solve: answer })
      await store.solve('ad000001')
      expect(store.error).toMatchObject({ code, retryable })
      expect(store.lastResult?.startsWith('We could not confirm that move') ?? false).toBe(unknown)
      expect(store.log[0]?.result).toContain(unknown ? 'Result unknown' : 'Not done')
      expect(store.autoplay).toBe(false)
      expect(posts(calls)).toHaveLength(1)
    },
  )

  it('offers no retry when a new game fails to start on the game-over screen', async () => {
    let starts = 0
    const { store } = await started({
      start: () => (starts++ === 0 ? json(GAME) : problem(503, 'UPSTREAM_UNAVAILABLE')),
      solve: () => json(solveResult({ lives: 0 })),
    })
    await store.solve('ad000001')
    expect(store.finished).toBe(true)
    await store.start()
    expect(store.error).toMatchObject({ code: 'UPSTREAM_UNAVAILABLE', retryable: false })
  })

  it('a first start that fails offers no retry', async () => {
    stubServer({ start: () => problem(503, 'UPSTREAM_UNAVAILABLE') })
    const store = useGameStore()
    await store.start()
    expect(store.game).toBeNull()
    expect(store.error).toMatchObject({ code: 'UPSTREAM_UNAVAILABLE', retryable: false })
  })

  it('does not call a rejected ad unknown when the reload then fails', async () => {
    const { store } = await started({
      solve: () => problem(409, 'AD_UNAVAILABLE'),
      advice: () => problem(503, 'UPSTREAM_UNAVAILABLE'),
    })
    await store.solve('ad000001')
    expect(store.error?.code).toBe('UPSTREAM_UNAVAILABLE')
    expect(store.lastResult).toBe('That ad is no longer available')
    expect(store.advice).toBeNull()
    expect(store.log[0]?.result).toContain('Not done')
  })

  it('does not reload again when the reload is rejected as well', async () => {
    const { store, calls } = await started({
      solve: () => problem(409, 'AD_UNAVAILABLE'),
      advice: () => problem(409, 'AD_UNAVAILABLE'),
    })
    await store.solve('ad000001')
    expect(store.error?.code).toBe('AD_UNAVAILABLE')
    expect(calls.filter((c) => c.endsWith('/advice'))).toHaveLength(1)
  })

  it('forgets that a POST failed when the next call starts', async () => {
    let down = false
    const { store } = await started({
      solve: () => problem(503, 'UPSTREAM_UNAVAILABLE'),
      advice: () => (down ? problem(503, 'UPSTREAM_UNAVAILABLE') : json(advice(solveMove))),
    })
    await store.solve('ad000001')
    expect(store.lastResult).toContain('could not confirm')
    store.lastResult = null
    down = true
    await store.retry()
    expect(store.error?.code).toBe('UPSTREAM_UNAVAILABLE')
    expect(store.lastResult).toBeNull()
  })

  it('saves a received result before the advice reload is done', async () => {
    let release!: (response: Response) => void
    const gate = new Promise<Response>((resolve) => {
      release = resolve
    })
    let gated = false
    const { store } = await started({
      advice: () => (gated ? gate : json(advice(solveMove))),
    })
    gated = true
    const pending = store.solve('ad000001')
    await vi.waitFor(() =>
      expect(JSON.parse(sessionStorage.getItem('bigbank.game') ?? '{}').game.turn).toBe(1),
    )
    release(json(advice(solveMove)))
    await pending
  })
})

describe('moves and log', () => {
  it('keeps the newest 50 log entries, oldest first', async () => {
    let n = 0
    const { store } = await started({
      solve: () => json(solveResult({ turn: ++n, message: `m${n}` })),
    })
    for (let i = 0; i < 52; i++) {
      await store.solve('ad000001')
    }
    expect(store.log).toHaveLength(50)
    expect(store.log[0]?.result).toBe('Success: m3')
    expect(store.log[49]?.result).toBe('Success: m52')
  })

  it('logs a rejected ad as not done', async () => {
    const { store } = await started({ solve: () => problem(409, 'AD_UNAVAILABLE') })
    store.game = { ...GAME, turn: 7 }
    await store.solve('ad000001')
    expect(store.log[0]).toMatchObject({
      turn: 7,
      move: 'SOLVE',
      id: 'ad000001',
      result: 'Not done: Text',
      message: 'Message ad000001',
    })
  })

  it('takes the reason from the rated ad when it is not the recommended one', async () => {
    const { store } = await started({
      advice: () => json(advice(solveMove, [ad('ad000001'), ad('ad000002')])),
    })
    await store.refreshAdvice()
    await store.solve('ad000002')
    expect(store.log[0]?.reason).toBe('rated ad000002')
  })

  it('makes no buy on a finished game', async () => {
    const { store, calls } = await started()
    store.game = { ...GAME, lives: 0 }
    await store.buy('hpot')
    expect(calls).toEqual([])
  })

  it('a new game clears the log and the last result of the old one', async () => {
    const { store } = await started()
    await store.solve('ad000001')
    expect(store.log).toHaveLength(1)
    await store.start()
    expect(store.log).toEqual([])
    expect(store.lastResult).toBeNull()
  })
})

describe('recommended', () => {
  it('is the rated ad that the solve move points to', () => {
    const store = useGameStore()
    store.advice = advice(solveMove, [ad('ad000002'), ad('ad000001')])
    expect(store.recommended?.ad.adId).toBe('ad000001')
  })

  it('is null for a buy move, for no advice and for an id that is not listed', () => {
    const store = useGameStore()
    expect(store.recommended).toBeNull()
    store.advice = advice({ type: 'BUY', id: 'ad000001' })
    expect(store.recommended).toBeNull()
    store.advice = advice({ type: 'SOLVE', id: 'zzzzzzzz' })
    expect(store.recommended).toBeNull()
  })
})

describe('recommendedItem', () => {
  it('is the shop item that the buy move points to', () => {
    const store = useGameStore()
    store.shop = [
      { id: 'cs', name: 'Claw sharpening', cost: 100 },
      { id: 'hpot', name: 'Healing potion', cost: 50 },
    ]
    store.advice = advice({ type: 'BUY', id: 'hpot' })
    expect(store.recommendedItem?.name).toBe('Healing potion')
  })

  it('is null for a solve move, for no advice and for an item that is not in the shop', () => {
    const store = useGameStore()
    store.shop = [{ id: 'hpot', name: 'Healing potion', cost: 50 }]
    expect(store.recommendedItem).toBeNull()
    store.advice = advice(solveMove)
    expect(store.recommendedItem).toBeNull()
    store.advice = advice({ type: 'BUY', id: 'zzz' })
    expect(store.recommendedItem).toBeNull()
  })
})

describe('storage', () => {
  const entry = { turn: 1, move: 'SOLVE', id: 'ad000001', reason: 'r', result: 'x' }
  const save = (value: unknown) => sessionStorage.setItem('bigbank.game', JSON.stringify(value))

  it('restores the log and keeps only the newest 50 entries', () => {
    save({ game: GAME, log: Array.from({ length: 60 }, (_, i) => ({ ...entry, turn: i })) })
    const store = useGameStore()
    expect(store.restore()).toBe(true)
    expect(store.log).toHaveLength(50)
    expect(store.log[0]?.turn).toBe(10)
    expect(store.log[49]?.turn).toBe(59)
  })

  it('keeps working when sessionStorage throws', async () => {
    const blocked = () => {
      throw new Error('blocked')
    }
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(blocked)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(blocked)
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(blocked)
    const { store } = await started({ solve: () => problem(404, 'GAME_NOT_FOUND') })
    expect(store.game).toEqual(GAME)
    expect(store.restore()).toBe(false)
    await store.solve('ad000001') // persists by removing the key
    expect(store.game).toBeNull()
  })
})
