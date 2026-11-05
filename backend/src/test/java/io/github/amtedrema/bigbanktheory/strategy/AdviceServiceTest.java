package io.github.amtedrema.bigbanktheory.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import io.github.amtedrema.bigbanktheory.mugloar.Ad;
import io.github.amtedrema.bigbanktheory.mugloar.GameState;
import io.github.amtedrema.bigbanktheory.mugloar.MugloarClient;
import io.github.amtedrema.bigbanktheory.mugloar.ShopItem;
import org.junit.jupiter.api.Test;

class AdviceServiceTest {

	private static final GameState STATE = new GameState("g1", 3, 0, 0, 0, 4);
	private static final Ad A = new Ad("a", "m", 100, 5, "Gamble", null);
	private static final Ad B = new Ad("b", "m", 90, 5, "Gamble", null);
	private static final List<ShopItem> SHOP = List.of(new ShopItem("hpot", "Healing potion", 50));

	private final MugloarClient client = mock(MugloarClient.class);
	private final Strategy strategy = mock(Strategy.class);
	private final AdviceService service = new AdviceService(client, strategy);

	@Test
	void loadsMessagesAndShopOnceAndPassesThemToTheStrategy() {
		Advice advice = new Advice(List.of(), Move.none(), "r");
		when(client.messages("g1")).thenReturn(List.of(A, B));
		when(client.shop("g1")).thenReturn(SHOP);
		when(strategy.decide(STATE, List.of(A, B), SHOP)).thenReturn(advice);

		assertThat(service.advise(STATE)).isSameAs(advice);
		verify(client).messages("g1");
		verify(client).shop("g1");
	}

	@Test
	void excludedAdsAreRemovedBeforeTheStrategySeesThem() {
		Advice advice = new Advice(List.of(), Move.none(), "r");
		when(client.messages("g1")).thenReturn(List.of(A, B));
		when(strategy.decide(STATE, List.of(B), SHOP)).thenReturn(advice);

		assertThat(service.advise(STATE, SHOP, Set.of("a"))).isSameAs(advice);
	}

}
