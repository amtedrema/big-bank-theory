import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

import App from '../App.vue'
import { useGameStore } from '@/stores/game'
import type { Move } from '@/api'
import { ad, adviceOf, GAME, SHOP, spyOnActions } from '@/components/__tests__/fixtures'

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

let mounted: VueWrapper | null = null

/** The store actions are replaced by spies unless a test needs them to really run. */
function mountApp(attachTo?: HTMLElement, spy = true) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useGameStore()
  const actions = spyOnActions(store, spy)
  const wrapper = mount(App, { global: { plugins: [pinia] }, attachTo })
  mounted = wrapper
  return { store, wrapper, actions }
}

const buttonLabels = (wrapper: VueWrapper) => wrapper.findAll('button').map((b) => b.text())
const button = (wrapper: VueWrapper, label: string) =>
  wrapper.findAll('button').find((b) => b.text() === label)

/** Answers the backend calls by their path; the solve answer is the only one a test changes. */
function stubBackend(solve: () => Response = () => json({}), failAdviceAfterFirst = false) {
  const calls: string[] = []
  let adviceCalls = 0
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init?: RequestInit) => {
      calls.push(`${init?.method} ${path}`)
      if (path.endsWith('/shop')) return json(SHOP)
      if (path.endsWith('/advice')) {
        if (failAdviceAfterFirst && adviceCalls++ > 0) {
          return json({ status: 503, detail: 'Not now', code: 'UPSTREAM_UNAVAILABLE' }, 503)
        }
        return json(adviceOf({ type: 'SOLVE', id: 'ad000001' }, [ad('ad000001')]))
      }
      if (path.endsWith('/solve')) return solve()
      return json(GAME)
    }),
  )
  return calls
}

describe('App', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => {
    mounted?.unmount()
    mounted = null
    vi.unstubAllGlobals()
  })

  it('shows the start button when there is no game', () => {
    const { wrapper } = mountApp()
    expect(buttonLabels(wrapper)).toEqual(['Start game'])
  })

  it('starts a game when Start game is pressed', async () => {
    const { wrapper, actions } = mountApp()
    await button(wrapper, 'Start game')?.trigger('click')
    expect(actions.start).toHaveBeenCalledTimes(1)
  })

  it('does not offer Start game while a call is running', async () => {
    const { store, wrapper } = mountApp()
    store.busy = true
    await wrapper.vm.$nextTick()
    expect(button(wrapper, 'Start game')?.attributes('disabled')).toBeDefined()
  })

  it('shows the error on the start screen after the game was lost', async () => {
    const { store, wrapper } = mountApp()
    store.error = { code: 'GAME_NOT_FOUND', message: 'The game no longer exists', retryable: false }
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[role="alert"]').text()).toContain('The game no longer exists')
    expect(buttonLabels(wrapper)).toEqual(['Start game'])
  })

  it('shows lives, gold and score as labelled values', async () => {
    const { store, wrapper } = mountApp()
    store.game = { ...GAME, lives: 2, gold: 35, score: 120, level: 1, turn: 7 }
    await wrapper.vm.$nextTick()
    const stats = wrapper.find('[aria-label="Game status"]')
    const pairs = stats
      .findAll('dl > div')
      .map((row) => [row.find('dt').text(), row.find('dd').text()])
    expect(pairs).toEqual([
      ['Score', '120'],
      ['Gold', '35'],
      ['Lives', '2'],
      ['Level', '1'],
      ['Turn', '7'],
    ])
  })

  it('shows the final score and no Solve buttons for a finished game', async () => {
    const { store, wrapper } = mountApp()
    store.game = { ...GAME, lives: 0, score: 480 }
    store.advice = adviceOf({ type: 'SOLVE', id: 'ad000001' }, [ad('ad000001')])
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('Game over')
    expect(wrapper.text()).toContain('480')
    expect(buttonLabels(wrapper)).toEqual(['New game', 'Auto-play'])
    expect(wrapper.find('.bot-controls button').attributes('aria-disabled')).toBe('true')
    expect(wrapper.find('.bot-status').text()).toBe('Game over')
  })

  it('keeps the decision log, with the move that ended the game, at game over', async () => {
    const { store, wrapper } = mountApp()
    store.game = { ...GAME, lives: 0, score: 480 }
    store.log = [
      {
        turn: 8,
        move: 'SOLVE',
        id: 'ad000001',
        reason: 'r',
        result: 'Failure: Too slow',
        message: 'Help a duck',
      },
    ]
    await wrapper.vm.$nextTick()
    const entry = wrapper.find('.bot-log li')
    expect(entry.text()).toContain('Turn 8: Solve')
    expect(entry.text()).toContain('Help a duck')
    expect(entry.text()).toContain('Failure: Too slow')
    expect(wrapper.findAll('h2').map((h) => h.text())).toEqual(['Game over', 'Auto-play'])
  })

  it('announces the final score at game over', async () => {
    const { store, wrapper } = mountApp()
    store.game = { ...GAME, lives: 0, score: 480 }
    store.lastResult = 'Failure: Too slow'
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[aria-live="polite"]').text()).toBe(
      'Game over. Final score: 480. Failure: Too slow',
    )
  })

  it('starts a new game from the game-over panel', async () => {
    const { store, wrapper, actions } = mountApp()
    store.game = { ...GAME, lives: 0 }
    await wrapper.vm.$nextTick()
    await button(wrapper, 'New game')?.trigger('click')
    expect(actions.start).toHaveBeenCalledTimes(1)
  })

  it('shows Retry for a retryable error and retries when it is pressed', async () => {
    const { store, wrapper, actions } = mountApp()
    store.game = GAME
    store.error = { code: 'UPSTREAM_UNAVAILABLE', message: 'Try later', retryable: true }
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[role="alert"]').text()).toContain('Try later')
    expect(buttonLabels(wrapper)).toContain('Retry')
    expect(buttonLabels(wrapper)).not.toContain('New game')
    await button(wrapper, 'Retry')?.trigger('click')
    expect(actions.retry).toHaveBeenCalledTimes(1)
  })

  it('offers New game instead of Retry for a non-retryable error in a running game', async () => {
    const { store, wrapper, actions } = mountApp()
    store.game = GAME
    store.error = { code: 'REQUEST_REJECTED', message: 'Rejected', retryable: false }
    await wrapper.vm.$nextTick()
    const alert = wrapper.find('[role="alert"]')
    expect(alert.text()).toContain('New game')
    expect(alert.text()).not.toContain('Retry')
    await alert.find('button').trigger('click')
    expect(actions.start).toHaveBeenCalledTimes(1)
  })

  it('does not offer New game in the error banner at game over', async () => {
    const { store, wrapper } = mountApp()
    store.game = { ...GAME, lives: 0 }
    store.error = { code: 'UPSTREAM_UNAVAILABLE', message: 'Try later', retryable: false }
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[role="alert"] button').exists()).toBe(false)
    expect(buttonLabels(wrapper)).toEqual(['New game', 'Auto-play'])
  })

  it('still shows the shop when the advice is missing after an error', async () => {
    const { store, wrapper } = mountApp()
    store.game = { ...GAME, gold: 60 }
    store.shop = SHOP
    store.advice = null
    store.error = { code: 'NETWORK', message: 'No connection', retryable: true }
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('Ads are not available')
    expect(wrapper.text()).toContain('Healing potion')
    expect(buttonLabels(wrapper)).toContain('Retry')
  })

  it('shows the next move above the ads and the shop', async () => {
    const { store, wrapper } = mountApp()
    store.game = GAME
    store.advice = adviceOf({ type: 'SOLVE', id: 'ad000001' }, [ad('ad000001')], 'Best odds')
    await wrapper.vm.$nextTick()
    const headings = wrapper.findAll('h2').map((h) => h.text())
    expect(headings).toEqual(['Auto-play', 'Next move', 'Ads', 'Shop'])
  })

  it('announces the last result in a polite live region', async () => {
    const { store, wrapper } = mountApp()
    store.game = GAME
    store.lastResult = 'Failure: The dragon was too slow'
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[aria-live="polite"]').text()).toBe('Failure: The dragon was too slow')
  })

  it('says it is working, without announcing it, while a call runs', async () => {
    const { store, wrapper } = mountApp()
    store.game = GAME
    store.busy = true
    await wrapper.vm.$nextTick()
    expect(wrapper.find('main').attributes('aria-busy')).toBe('true')
    expect(wrapper.find('.working').text()).toContain('Working')
    expect(wrapper.find('.working').attributes('aria-hidden')).toBe('true')
    store.busy = false
    await wrapper.vm.$nextTick()
    expect(wrapper.find('main').attributes('aria-busy')).toBe('false')
    expect(wrapper.find('.working').exists()).toBe(false)
  })

  it('restores a saved game and reloads shop and advice without sending any move', async () => {
    sessionStorage.setItem(
      'bigbank.game',
      JSON.stringify({ game: { ...GAME, score: 90 }, log: [] }),
    )
    const calls = stubBackend()
    const { wrapper } = mountApp(undefined, false)
    await flushPromises()
    expect(calls).toEqual(['GET /api/games/abcd1234/shop', 'POST /api/games/abcd1234/advice'])
    expect(wrapper.text()).toContain('90')
    expect(wrapper.text()).toContain('Message ad000001')
  })

  it('restores a finished game without any network call', async () => {
    sessionStorage.setItem(
      'bigbank.game',
      JSON.stringify({ game: { ...GAME, lives: 0, score: 300 }, log: [] }),
    )
    const fetchMock = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', fetchMock)
    const { wrapper } = mountApp(undefined, false)
    await flushPromises()
    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Game over')
    expect(wrapper.text()).toContain('300')
  })
})

// The pressed button is removed or disabled by the move; the browser then drops focus to <body>.
describe('keyboard focus after a move', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => {
    mounted?.unmount()
    mounted = null
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  function attached() {
    const host = document.createElement('div')
    document.body.appendChild(host)
    return host
  }

  it('moves focus to the result line and announces the final score when the last life is lost', async () => {
    stubBackend(() =>
      json({ success: false, lives: 0, gold: 0, score: 33, turn: 5, message: 'You lost' }),
    )
    const { store, wrapper } = mountApp(attached(), false)
    await store.start()
    await flushPromises()
    ;(wrapper.find('li button').element as HTMLButtonElement).focus()
    await store.solve('ad000001')
    await flushPromises()
    const status = wrapper.find('[role="status"]')
    expect(document.activeElement).toBe(status.element)
    expect(status.text()).toContain('Final score: 33')
  })

  it('moves focus to the error without scrolling when the game is gone', async () => {
    stubBackend(() => json({ status: 404, detail: 'Game not found', code: 'GAME_NOT_FOUND' }, 404))
    const { store, wrapper } = mountApp(attached(), false)
    await store.start()
    await flushPromises()
    ;(wrapper.find('li button').element as HTMLButtonElement).focus()
    const focus = vi.spyOn(HTMLElement.prototype, 'focus')
    await store.solve('ad000001')
    await flushPromises()
    expect(store.game).toBeNull()
    expect(document.activeElement).toBe(wrapper.find('[role="alert"]').element)
    expect(focus).toHaveBeenCalledWith({ preventScroll: true })
    focus.mockRestore()
  })

  it('prefers the error to the result line when both are on the page', async () => {
    stubBackend(() => json({ status: 409, detail: 'Gone', code: 'AD_UNAVAILABLE' }, 409), true)
    const { store, wrapper } = mountApp(attached(), false)
    await store.start()
    await flushPromises()
    ;(wrapper.find('li button').element as HTMLButtonElement).focus()
    await store.solve('ad000001')
    await flushPromises()
    expect(store.game).not.toBeNull()
    expect(store.error).not.toBeNull()
    expect(document.activeElement).toBe(wrapper.find('[role="alert"]').element)
  })

  it('focuses the result line without scrolling after a successful move', async () => {
    stubBackend()
    const { store, wrapper } = mountApp(attached(), false)
    const scroll = vi.fn<() => void>()
    Element.prototype.scrollIntoView = scroll
    ;(wrapper.find('button').element as HTMLButtonElement).focus()
    await store.start()
    await flushPromises()
    expect(document.activeElement).toBe(wrapper.find('[role="status"]').element)
    expect(scroll).not.toHaveBeenCalled()
  })

  it('scrolls to the error only when it is above the window', async () => {
    const scroll = vi.fn<() => void>()
    Element.prototype.scrollIntoView = scroll
    const rect = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect')
    stubBackend(() => json({ status: 404, detail: 'Game not found', code: 'GAME_NOT_FOUND' }, 404))
    const { store, wrapper } = mountApp(attached(), false)
    await store.start()
    await flushPromises()
    ;(wrapper.find('li button').element as HTMLButtonElement).focus()
    rect.mockReturnValue({ top: 20 } as DOMRect)
    await store.solve('ad000001')
    await flushPromises()
    expect(scroll).not.toHaveBeenCalled()

    store.error = null
    await store.start()
    await flushPromises()
    ;(wrapper.find('li button').element as HTMLButtonElement).focus()
    rect.mockReturnValue({ top: -50 } as DOMRect)
    await store.solve('ad000001')
    await flushPromises()
    expect(scroll).toHaveBeenCalledTimes(1)
    rect.mockRestore()
  })

  it('does not scroll to the result line when no button had focus', async () => {
    const scroll = vi.fn<() => void>()
    Element.prototype.scrollIntoView = scroll
    stubBackend()
    const { store } = mountApp(attached(), false)
    await store.start()
    await flushPromises()
    expect(document.activeElement).toBe(document.body)
    const rect = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect')
    rect.mockReturnValue({ top: -500 } as DOMRect)
    await store.solve('ad000001')
    await flushPromises()
    expect(store.error).toBeNull()
    expect(scroll).not.toHaveBeenCalled()
    rect.mockRestore()
  })

  it('still scrolls to an error above the window when no button had focus', async () => {
    const scroll = vi.fn<() => void>()
    Element.prototype.scrollIntoView = scroll
    stubBackend(() => json({ status: 503, detail: 'Not now', code: 'UPSTREAM_UNAVAILABLE' }, 503))
    const { store } = mountApp(attached(), false)
    await store.start()
    await flushPromises()
    expect(document.activeElement).toBe(document.body)
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
      top: -2646,
    } as DOMRect)
    await store.solve('ad000001')
    await flushPromises()
    expect(store.error).not.toBeNull()
    expect(scroll).toHaveBeenCalledTimes(1)
    expect(document.activeElement).toBe(document.body)
    vi.mocked(HTMLElement.prototype.getBoundingClientRect).mockRestore()
  })

  it('leaves focus on a button that is still there afterwards', async () => {
    stubBackend()
    const { store, wrapper } = mountApp(attached(), false)
    await store.start()
    await flushPromises()
    const solve = wrapper.find('li button').element as HTMLButtonElement
    solve.focus()
    await store.refreshAdvice()
    await flushPromises()
    expect(document.activeElement).toBe(solve)
  })
})

describe('auto-play in the app', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  })
  afterEach(() => {
    mounted?.unmount()
    mounted = null
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  /** Every solve moves the turn on, so the loop keeps going until it is stopped. */
  function stubPlayableBackend(
    failFrom = Infinity,
    move: Move = { type: 'SOLVE', id: 'ad000001' },
  ) {
    let solves = 0
    const calls: string[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn(async (path: string, init?: RequestInit) => {
        calls.push(`${init?.method} ${path}`)
        if (path.endsWith('/shop')) return json(SHOP)
        if (path.endsWith('/advice')) {
          return json(adviceOf(move, [ad('ad000001')], 'Nothing to do'))
        }
        if (path.endsWith('/solve')) {
          solves++
          if (solves >= failFrom) {
            return json({ status: 503, detail: 'Not now', code: 'UPSTREAM_UNAVAILABLE' }, 503)
          }
          return json({
            success: true,
            lives: 3,
            gold: 0,
            score: solves * 10,
            turn: solves,
            message: `Done ${solves}`,
          })
        }
        return json(GAME)
      }),
    )
    return calls
  }

  async function startedApp() {
    const host = document.createElement('div')
    document.body.appendChild(host)
    const { store, wrapper } = mountApp(host, false)
    await store.start()
    await vi.advanceTimersByTimeAsync(0)
    return { store, wrapper }
  }

  const toggleOf = (wrapper: VueWrapper) =>
    wrapper.find('.bot-controls button').element as HTMLButtonElement

  it('turns the manual buttons off while auto-play runs, and back on after Stop', async () => {
    stubPlayableBackend()
    const { store, wrapper } = await startedApp()
    const manual = () =>
      wrapper.findAll('main button').filter((b) => !b.element.closest('.bot-controls'))
    expect(manual().some((b) => b.attributes('disabled') === undefined)).toBe(true)
    await wrapper.find('.bot-controls button').trigger('click')
    // Between two steps the store is not busy; the buttons still stay off.
    for (let i = 0; i < 4; i++) {
      await vi.advanceTimersByTimeAsync(250)
      store.busy = false
      await wrapper.vm.$nextTick()
      expect(manual().every((b) => b.attributes('disabled') !== undefined)).toBe(true)
    }
    expect(wrapper.find('.bot-panel').text()).toContain('Stop auto-play to make your own moves')
    await wrapper.find('.bot-controls button').trigger('click')
    await vi.advanceTimersByTimeAsync(2000)
    expect(store.autoplay).toBe(false)
    expect(manual().some((b) => b.attributes('disabled') === undefined)).toBe(true)
    expect(wrapper.find('.bot-panel').text()).not.toContain('Stop auto-play to make')
  })

  it('hides the working hint while auto-play runs, and shows it for a manual move', async () => {
    stubPlayableBackend()
    const { store, wrapper } = await startedApp()
    store.busy = true
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.working').exists()).toBe(true)
    store.busy = false
    await wrapper.vm.$nextTick()
    await wrapper.find('.bot-controls button').trigger('click')
    // Busy goes up and down during auto-play; the hint stays away.
    expect(store.autoplay).toBe(true)
    for (let i = 0; i < 6; i++) {
      await vi.advanceTimersByTimeAsync(300)
      store.busy = true
      await wrapper.vm.$nextTick()
      expect(wrapper.find('.working').exists()).toBe(false)
      store.busy = false
    }
  })

  it('keeps focus on the toggle through every step and when Stop is pressed by keyboard', async () => {
    stubPlayableBackend()
    const { store, wrapper } = await startedApp()
    const scroll = vi.fn<() => void>()
    Element.prototype.scrollIntoView = scroll
    const toggle = toggleOf(wrapper)
    toggle.focus()
    await wrapper.find('.bot-controls button').trigger('click')
    const focus = vi.spyOn(HTMLElement.prototype, 'focus')
    for (let i = 0; i < 6; i++) {
      await vi.advanceTimersByTimeAsync(300)
      expect(document.activeElement).toBe(toggle)
    }
    expect(store.log.length).toBeGreaterThan(2)
    expect(toggle.textContent?.trim()).toBe('Stop')

    await wrapper.find('.bot-controls button').trigger('click')
    await vi.advanceTimersByTimeAsync(2000)
    expect(store.autoplay).toBe(false)
    expect(toggleOf(wrapper)).toBe(toggle)
    expect(toggle.textContent?.trim()).toBe('Auto-play')
    expect(document.activeElement).toBe(toggle)
    expect(focus).not.toHaveBeenCalled()
    expect(scroll).not.toHaveBeenCalled()
  })

  it('stops with the error shown, offers Auto-play again, and plays on after Retry', async () => {
    stubPlayableBackend(2)
    const { store, wrapper } = await startedApp()
    await wrapper.find('.bot-controls button').trigger('click')
    await vi.advanceTimersByTimeAsync(3000)
    expect(store.autoplay).toBe(false)
    expect(wrapper.find('[role="alert"]').text()).toContain('Retry')
    expect(toggleOf(wrapper).textContent?.trim()).toBe('Auto-play')
    expect(wrapper.find('.bot-status').text()).toBe('Stopped')
    expect(wrapper.find('.bot-log').text()).toContain('Result unknown')

    await button(wrapper, 'Retry')?.trigger('click')
    await vi.advanceTimersByTimeAsync(0)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(toggleOf(wrapper).disabled).toBe(false)
    expect(toggleOf(wrapper).getAttribute('aria-disabled')).toBe('false')
    await wrapper.find('.bot-controls button').trigger('click')
    expect(store.autoplay).toBe(true)
  })

  it('says why auto-play stopped in the result line', async () => {
    stubPlayableBackend(Infinity, { type: 'NONE', id: null })
    const { store, wrapper } = await startedApp()
    await wrapper.find('.bot-controls button').trigger('click')
    await vi.advanceTimersByTimeAsync(100)
    expect(store.autoplay).toBe(false)
    expect(wrapper.find('.result').text()).toBe('Auto-play stopped: no playable move')
    expect(wrapper.find('.bot-status').text()).toBe('Stopped')
    expect(wrapper.find('.bot-log li').text()).toContain('No playable move')
  })
})
