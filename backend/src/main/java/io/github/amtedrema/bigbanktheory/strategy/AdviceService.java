package io.github.amtedrema.bigbanktheory.strategy;

import java.util.List;
import java.util.Set;

import io.github.amtedrema.bigbanktheory.mugloar.Ad;
import io.github.amtedrema.bigbanktheory.mugloar.GameState;
import io.github.amtedrema.bigbanktheory.mugloar.MugloarClient;
import io.github.amtedrema.bigbanktheory.mugloar.ShopItem;
import org.springframework.stereotype.Service;

/** Loads ads and shop for a game and asks the strategy. Used by the bot and the REST API. */
@Service
public class AdviceService {

	private final MugloarClient client;
	private final Strategy strategy;

	public AdviceService(MugloarClient client, Strategy strategy) {
		this.client = client;
		this.strategy = strategy;
	}

	public Advice advise(GameState state) {
		return advise(state, client.shop(state.gameId()), Set.of());
	}

	/** The excluded ads are the ones the server rejected earlier in this turn. */
	public Advice advise(GameState state, List<ShopItem> shop, Set<String> excludedAdIds) {
		List<Ad> ads = client.messages(state.gameId()).stream()
				.filter(ad -> !excludedAdIds.contains(ad.adId())).toList();
		return strategy.decide(state, ads, shop);
	}
}
