import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'

import ShopPanel from '../ShopPanel.vue'
import { ad, adviceOf, playingStore, SHOP, spyOnActions } from './fixtures'

type Store = ReturnType<typeof playingStore>['store']

function mountWith(gold: number, setup: (store: Store) => void = () => {}, game = {}) {
  const { pinia, store } = playingStore({ gold, ...game })
  const actions = spyOnActions(store)
  store.shop = SHOP
  setup(store)
  return { wrapper: mount(ShopPanel, { global: { plugins: [pinia] } }), actions }
}

const disabled = (wrapper: ReturnType<typeof mountWith>['wrapper'], index: number) =>
  wrapper.findAll('button')[index]?.attributes('disabled') !== undefined

describe('ShopPanel', () => {
  it('disables Buy while auto-play runs, even between two moves', () => {
    const { wrapper } = mountWith(60, (store) => {
      store.autoplay = true
    })
    expect(disabled(wrapper, 0)).toBe(true)
  })

  it('enables Buy when the gold is enough, and only for what is affordable', () => {
    const { wrapper } = mountWith(60)
    expect(disabled(wrapper, 0)).toBe(false)
    expect(disabled(wrapper, 1)).toBe(true)
  })

  it('enables Buy when the gold is exactly the cost', () => {
    const { wrapper } = mountWith(50)
    expect(disabled(wrapper, 0)).toBe(false)
    expect(wrapper.text()).not.toContain('Need 0')
  })

  it('says how much gold is missing for what cannot be bought yet', () => {
    const { wrapper } = mountWith(40)
    const cards = wrapper.findAll('li')
    expect(cards[0]?.text()).toContain('Need 10 more gold')
    expect(cards[1]?.text()).toContain('Need 60 more gold')
    const rich = mountWith(500).wrapper
    expect(rich.text()).not.toContain('Need')
  })

  it('shows the cost of each item', () => {
    const { wrapper } = mountWith(0)
    expect(wrapper.text()).toContain('Healing potion')
    expect(wrapper.text()).toContain('50 gold')
  })

  it('buys the item whose button was pressed', async () => {
    const { wrapper, actions } = mountWith(500)
    await wrapper.findAll('button')[1]?.trigger('click')
    expect(actions.buy).toHaveBeenCalledTimes(1)
    expect(actions.buy).toHaveBeenCalledWith('cs')
  })

  it('gives each Buy button the name of its item', () => {
    const { wrapper } = mountWith(500)
    const names = wrapper.findAll('button').map((b) => b.attributes('aria-label'))
    expect(names).toEqual(['Buy Healing potion', 'Buy Claw sharpening'])
  })

  it('disables Buy while busy', () => {
    const { wrapper } = mountWith(500, (store) => {
      store.busy = true
    })
    expect(wrapper.findAll('button').every((b) => b.attributes('disabled') !== undefined)).toBe(
      true,
    )
  })

  it('disables Buy when the game is over, whatever the gold', () => {
    const { wrapper } = mountWith(500, () => {}, { lives: 0 })
    expect(wrapper.findAll('button').every((b) => b.attributes('disabled') !== undefined)).toBe(
      true,
    )
  })

  it('marks the recommended purchase', () => {
    const { wrapper } = mountWith(60, (store) => {
      store.advice = adviceOf({ type: 'BUY', id: 'hpot' }, [ad('ad000001')], 'Lives are low')
    })
    const cards = wrapper.findAll('li')
    expect(cards[0]?.text()).toContain('Recommended')
    expect(cards[0]?.classes()).toContain('recommended')
    expect(cards[1]?.text()).not.toContain('Recommended')
    expect(cards[1]?.classes()).not.toContain('recommended')
  })

  it('does not recommend a purchase when the advice is to solve', () => {
    const { wrapper } = mountWith(60, (store) => {
      store.advice = adviceOf({ type: 'SOLVE', id: 'ad000001' }, [ad('ad000001')])
    })
    expect(wrapper.text()).not.toContain('Recommended')
  })

  it('says it is loading while the shop is on its way', () => {
    const { pinia, store } = playingStore()
    store.busy = true
    const wrapper = mount(ShopPanel, { global: { plugins: [pinia] } })
    expect(wrapper.text()).toContain('Loading the shop...')
    expect(wrapper.text()).not.toContain('not available')
  })

  it('says so when the shop is empty and nothing is loading', () => {
    const { pinia } = playingStore()
    const wrapper = mount(ShopPanel, { global: { plugins: [pinia] } })
    expect(wrapper.text()).toContain('not available')
    expect(wrapper.text()).not.toContain('Loading')
  })
})
