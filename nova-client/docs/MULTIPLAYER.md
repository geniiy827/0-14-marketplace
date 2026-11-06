# NOVA: playing with friends (alpha16)

The world runs on the host's phone. The NOVA server (NovaAccounts plugin on Genisys) only helps phones find each
other: it knows who is online and which friend world is open, and it passes packets on when two phones can't reach
each other directly. When the direct path works (most Wi-Fi and home networks), game packets go phone to phone.

## How it works
- Each signed-in client keeps one UDP "tunnel" socket to the server (`NovaNet.java`). It sends presence every 15 s,
  or every 3 s while it hosts or plays. Account requests use the same socket, so the server learns its public address.
- Opening a world: the create-world screen has a **Сеть** (Network) button in the header, next to **Дополнительно**.
  It opens these settings: network game on/off (off by default), who can join (friends / invited only), and max
  players (a 2..10 slider, 5 by default). Settings are saved per world (`files/nova/networlds.properties`).
- When the host enters that world, the game starts its LAN server. libnova catches `RakNetInstance::host` (the port,
  up to 10 connections), Java reads it through `nativeNetState`, and presence carries `world {name, mode, max, players}`.
- **Друзья** (Friends) button on the start screen, above **Хранилище**. It has two tabs:
  - **+**: add a friend by tag;
  - Friends: requests (Принять / Отклонить), friends with their status ("В игре: Мир 2/5"), **Подключиться**, **Удалить**.
- Joining: `join {tag}` gives a link id and the host's tunnel address. The server pokes the host, and the host learns
  the joiner's address from its next presence. Both send punches, then data:
  `FE FD 4E 01 02 | link | game packet`. A packet goes direct if a packet from the peer arrived in the last 6 s;
  otherwise it goes through the server.
  - Joiner: the game connects to `127.0.0.1:<local port>` (`MinecraftClient::joinMultiplayer`), as to a LAN game.
  - Host: each link has a local socket that talks to `127.0.0.1:<game port>`. Any number of worlds and links.
- Pause menu: **Друзья** (between Параметры and Выйти) appears only in a world opened to friends. It lists friends
  who are online, each with a **Пригласить** button.
- An invited friend gets a banner at the top: "Вас пригласили", with **Войти** / **Нет** buttons (25 s).

## Server ops (Social.php)
`friends.list`, `friends.request|accept|decline|remove {tag}`, `presence {world, keep}`, `invite {tag}`,
`invite.decline {from}`, `join {tag}`, `leave {link}`. Relay: packets of type 0x02 from a link's address go to
its other end. The max-player limit and invite-only mode are checked on `join`.

## Native (native/net.inc)
- NOVA buttons: `add_button()` makes a toggle row whose OptionButton covers the row. It is drawn as the game's
  `Touch::TButton` (OptionButton vtable slot 3; OptionsItem slot 5 for the layout).
- Create-world screen: a `Touch::HeaderButton` (like "Advanced"), added to buttonList after every setupPositions;
  click → `W|level name`.
- Joining: `ScreenChooser::pushProgressScreen` + `MinecraftClient::joinMultiplayer` on the game thread.
- Start and pause JSON are served by Java per state (markers `/*nova:in*/`, `/*nova:out*/`, `/*nova:net*/`,
  `/*nova:quit*/`, `/*nova:panel*/`). Then `MinecraftClient::handleReloadUIDefinitions` (+ `setStartMenuScreen`).
- Invite banner: drawn with the toast; taps on its buttons are taken out of `Multitouch::feed` (C hook).
