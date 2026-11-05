# big-bank-theory

A bot and a web app that play [Dragons of Mugloar](https://dragonsofmugloar.com/) with one shared strategy. Built as a Bigbank test task covering both adventures.

## Running

With Docker, no local Java or Node needed:

```sh
docker compose up --build   # web app on http://localhost:8080
docker compose run --rm app --spring.profiles.active=bot --games=3 --target-score=1000000
```

Without Docker you need Java 21, Node.js 22.18+ or 24.12+ and pnpm. Start the backend and the frontend in two terminals, then open http://localhost:5173:

```sh
cd backend && ./mvnw spring-boot:run
cd frontend && pnpm install && pnpm dev
```

The bot:

```sh
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=bot -Dspring-boot.run.arguments="--games=3 --target-score=1000000"
```

`--games` defaults to 1. Without `--target-score` a game runs until its lives run out, which may be never, so stop it by hand. The commands assume a POSIX shell such as Git Bash. Playing needs internet access to the game server. No secrets are needed.

## Tests

The tests do not call the game server. CI runs these commands and a Docker build.

```sh
cd backend && ./mvnw test
cd frontend && pnpm exec vitest run
cd frontend && pnpm lint    # applies fixes, so it can change files
cd frontend && pnpm build   # includes the type check
```

## How it works

`backend/` is Java 21 and Spring Boot: `mugloar` calls the game server, `strategy` picks moves, `bot` plays from the command line and `web` is the REST API. `frontend/` is Vue 3, TypeScript and Pinia: `api.ts` calls the backend and `stores/game.ts` holds the state, error handling and auto-play.

The backend keeps no game state. The browser keeps its copy in `sessionStorage`, restores it after a reload and sends it with each advice request.

The Mugloar API differs from its docs, so `MugloarClient` validates its responses and turns its HTML errors into typed errors. The REST API validates its input and returns errors as `application/problem+json` with a `code`. The web app never repeats a move after an error, because a lost answer can hide a move that did happen. The bot waits out an unavailable server and then decides its move again.

## Strategy

The bot and the web app's advice use the same rules, in this order:

1. At 1 life or fewer, buy a healing potion if gold allows.
2. While some ad on offer is not certain and gold covers the next level item plus 50, buy that item.
3. Otherwise solve the ad with the highest expected value, `p * reward - (1 - p) * 454`, with `p` from the ad's difficulty label. `Steal` ads have another 454 taken off and known trap ads get `p = 0`.

The success rates come from real games and 454 was tuned on them. The measuring code is not in this repository. In those games rewards grew with the level, and ads got harder with each turn and easier with each level, which is why rule 2 keeps buying. Solved `Steal` ads seem to bring trap ads later, but this is unproven.

## Trade-offs

- Buying stops when every ad is certain. This needs no fitted formula, but gold piles up unused: 70 million in the longest game.
- Avoiding `Steal` ads loses a little early income.
- There is no turn limit, because the task says to play until the lives run out. `--target-score` stops a game that would otherwise keep going.
- A stateless backend has nothing to store or clean up, but its advice is only as good as the state the browser sends.

## Results

| Real games | Target | Scores |
| --- | --- | --- |
| Earlier rule for when to stop buying | 100,000 | 100,707, 14,714, 8,079 |
| Current code | 10,000,000 | 10,018,208, 10,004,498, 6,560 |
| Current code | none | 71,304,209, stopped by hand at turn 6,530 with lives left |

All 7 passed 1000 points. 3 of them died at turns 86 to 162. One game stopped at turn 13 when its target was dropped is left out. The sample is small because long games take thousands of requests.

## Use of AI tools

Built with Claude Code (Claude Opus 5.5, Fable 5.1 and Sonnet 5.5). The author set the goals, made the decisions behind the plan and approved it. AI agents wrote the code, the tests and this README, reviewed and tested the work, ran the experiments and the real games, and made many detailed decisions, such as the life value.
