import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'

import AdList from '../AdList.vue'
import { ad, adviceOf, playingStore, spyOnActions } from './fixtures'

type Store = ReturnType<typeof playingStore>['store']

function mountWith(setup: (store: Store) => void, game = {}) {
  const { pinia, store } = playingStore(game)
  const actions = spyOnActions(store)
  setup(store)
  return { wrapper: mount(AdList, { global: { plugins: [pinia] } }), actions }
}

const twoAds = [ad('ad000001'), ad('ad000002')]
const solveFirst = (store: Store) => {
  store.advice = adviceOf({ type: 'SOLVE', id: 'ad000001' }, twoAds)
}

describe('AdList', () => {
  it('marks the recommended ad and lists it first', () => {
    const { wrapper } = mountWith(solveFirst)
    const cards = wrapper.findAll('li')
    expect(cards).toHaveLength(2)
    expect(cards[0]?.text()).toContain('Recommended')
    expect(cards[0]?.text()).toContain('Message ad000001')
    expect(cards[0]?.classes()).toContain('recommended')
    expect(cards[1]?.text()).not.toContain('Recommended')
    expect(cards[1]?.classes()).not.toContain('recommended')
  })

  it('puts the recommended ad first even when the strategy listed another ad above it', () => {
    const { wrapper } = mountWith((store) => {
      store.advice = adviceOf({ type: 'SOLVE', id: 'ad000002' }, twoAds)
    })
    const cards = wrapper.findAll('li')
    expect(cards[0]?.text()).toContain('Message ad000002')
    expect(cards[0]?.text()).toContain('Recommended')
    expect(cards[1]?.text()).toContain('Message ad000001')
  })

  it('keeps the strategy order when no ad is recommended', () => {
    const { wrapper } = mountWith((store) => {
      store.advice = adviceOf({ type: 'BUY', id: 'hpot' }, twoAds)
    })
    const cards = wrapper.findAll('li')
    expect(cards[0]?.text()).toContain('Message ad000001')
    expect(wrapper.text()).not.toContain('Recommended')
  })

  it('shows reward, chance, expected value and expiry for each ad', () => {
    const { wrapper } = mountWith(solveFirst)
    const text = wrapper.text()
    expect(text).toContain('20 gold')
    expect(text).toContain('80%')
    expect(text).toContain('16.0')
    expect(text).toContain('5 turns')
  })

  it('says turn, not turns, for an ad that expires in one turn', () => {
    const { wrapper } = mountWith((store) => {
      store.advice = adviceOf({ type: 'SOLVE', id: 'ad000001' }, [ad('ad000001', { expiresIn: 1 })])
    })
    expect(wrapper.text()).toContain('1 turn')
    expect(wrapper.text()).not.toContain('1 turns')
  })

  it('shows the reason on the other ads, not again on the recommended one', () => {
    const { wrapper } = mountWith(solveFirst)
    const cards = wrapper.findAll('li')
    expect(cards[0]?.text()).not.toContain('Rated ad000001')
    expect(cards[1]?.text()).toContain('Rated ad000002')
  })

  it('shows Unknown for the chance and the expected value of an unknown ad', () => {
    const { wrapper } = mountWith((store) => {
      const advice = adviceOf({ type: 'SOLVE', id: 'ad000001' }, [ad('ad000001')])
      advice.ads[0]!.label = 'UNKNOWN'
      advice.ads[0]!.probability = 0
      advice.ads[0]!.expectedValue = -454
      store.advice = advice
    })
    const values = wrapper.findAll('dd').map((dd) => dd.text())
    expect(values).toEqual(['20 gold', 'Unknown', 'Unknown', 'Unknown', '5 turns'])
    expect(wrapper.text()).not.toContain('454')
    expect(wrapper.text()).not.toContain('0%')
  })

  it('solves the ad whose button was pressed', async () => {
    const { wrapper, actions } = mountWith(solveFirst)
    await wrapper.findAll('button')[1]?.trigger('click')
    expect(actions.solve).toHaveBeenCalledTimes(1)
    expect(actions.solve).toHaveBeenCalledWith('ad000002')
  })

  it('gives each Solve button the name of its ad', () => {
    const { wrapper } = mountWith(solveFirst)
    const names = wrapper.findAll('button').map((b) => b.attributes('aria-label'))
    expect(names).toEqual(['Solve: Message ad000001', 'Solve: Message ad000002'])
  })

  it('disables every Solve button while busy', () => {
    const { wrapper } = mountWith((store) => {
      solveFirst(store)
      store.busy = true
    })
    const buttons = wrapper.findAll('button')
    expect(buttons).toHaveLength(2)
    expect(buttons.every((b) => b.attributes('disabled') !== undefined)).toBe(true)
  })

  it('disables every Solve button when the game is over', () => {
    const { wrapper } = mountWith(solveFirst, { lives: 0 })
    const buttons = wrapper.findAll('button')
    expect(buttons).toHaveLength(2)
    expect(buttons.every((b) => b.attributes('disabled') !== undefined)).toBe(true)
  })

  it('disables an ad that could not be decoded and says why', () => {
    const { wrapper } = mountWith((store) => {
      store.advice = adviceOf({ type: 'SOLVE', id: 'ad000001' }, [
        ad('ad000001'),
        ad('ad000002', { encrypted: 'c2VjcmV0' }),
      ])
    })
    const cards = wrapper.findAll('li')
    expect(cards[0]?.find('button').attributes('disabled')).toBeUndefined()
    expect(cards[1]?.find('button').attributes('disabled')).toBeDefined()
    expect(cards[1]?.text()).toContain('cannot be solved')
    expect(cards[0]?.text()).not.toContain('cannot be solved')
  })

  it('keeps an ad with an unknown difficulty solvable', () => {
    const { wrapper } = mountWith((store) => {
      const advice = adviceOf({ type: 'SOLVE', id: 'ad000001' }, [ad('ad000001')])
      advice.ads[0]!.label = 'UNKNOWN'
      store.advice = advice
    })
    expect(wrapper.find('button').attributes('disabled')).toBeUndefined()
  })

  it('says so when there are no ads', () => {
    const { wrapper } = mountWith((store) => {
      store.advice = adviceOf({ type: 'NONE', id: null }, [])
    })
    expect(wrapper.text()).toContain('There are no ads right now.')
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('says it is loading while the first advice is on its way', () => {
    const { wrapper } = mountWith((store) => {
      store.busy = true
    })
    expect(wrapper.text()).toContain('Loading ads...')
    expect(wrapper.text()).not.toContain('not available')
  })

  it('explains the missing advice when nothing is loading', () => {
    const { wrapper } = mountWith(() => {})
    expect(wrapper.text()).toContain('Ads are not available')
    expect(wrapper.text()).not.toContain('Loading')
    expect(wrapper.find('button').exists()).toBe(false)
  })
})
