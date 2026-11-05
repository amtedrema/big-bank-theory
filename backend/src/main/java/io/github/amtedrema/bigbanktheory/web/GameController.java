package io.github.amtedrema.bigbanktheory.web;

import java.util.List;

import io.github.amtedrema.bigbanktheory.mugloar.BuyResult;
import io.github.amtedrema.bigbanktheory.mugloar.GameState;
import io.github.amtedrema.bigbanktheory.mugloar.MugloarClient;
import io.github.amtedrema.bigbanktheory.mugloar.ShopItem;
import io.github.amtedrema.bigbanktheory.mugloar.SolveResult;
import io.github.amtedrema.bigbanktheory.strategy.Advice;
import io.github.amtedrema.bigbanktheory.strategy.AdviceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The REST API of the web app. The browser sends the state with the advice request, and ids are
 * checked before they reach the Mugloar URL.
 */
@RestController
@RequestMapping("/api/games")
public class GameController {

	private static final String GAME_ID = "[A-Za-z0-9]{8}";
	private static final String AD_ID = "[A-Za-z0-9]{8}";
	private static final String ITEM_ID = "[a-z]{1,16}";

	private final MugloarClient client;
	private final AdviceService adviceService;

	public GameController(MugloarClient client, AdviceService adviceService) {
		this.client = client;
		this.adviceService = adviceService;
	}

	@PostMapping
	public GameState start() {
		return client.start();
	}

	@PostMapping("/{gameId}/advice")
	public Advice advice(@PathVariable @Pattern(regexp = GAME_ID) String gameId,
			@Valid @RequestBody GameState state) {
		if (!gameId.equals(state.gameId())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Game id in path and body differ");
		}
		return adviceService.advise(state);
	}

	@PostMapping("/{gameId}/ads/{adId}/solve")
	public SolveResult solve(@PathVariable @Pattern(regexp = GAME_ID) String gameId,
			@PathVariable @Pattern(regexp = AD_ID) String adId) {
		return client.solve(gameId, adId);
	}

	@GetMapping("/{gameId}/shop")
	public List<ShopItem> shop(@PathVariable @Pattern(regexp = GAME_ID) String gameId) {
		return client.shop(gameId);
	}

	@PostMapping("/{gameId}/shop/{itemId}/buy")
	public BuyResult buy(@PathVariable @Pattern(regexp = GAME_ID) String gameId,
			@PathVariable @Pattern(regexp = ITEM_ID) String itemId) {
		return client.buy(gameId, itemId);
	}
}
