package io.github.amtedrema.bigbanktheory.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amtedrema.bigbanktheory.bot.BotRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;

/** The web classes must not stop the bot profile, which starts without a web server. */
@SpringBootTest(properties = "spring.profiles.active=bot")
class BotProfileStartupTest {

	@Autowired
	ApplicationContext context;

	/** Replaced, so the test never plays a game. */
	@MockitoBean
	BotRunner runner;

	@Test
	void botProfileStartsWithoutAWebContextAndStillHasTheController() {
		assertThat(context).isNotInstanceOf(WebApplicationContext.class);
		assertThat(context.getBeansOfType(GameController.class)).hasSize(1);
	}
}
