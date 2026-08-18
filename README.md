# SpawnCape

A Paper 26.2 plugin that adds a single unique **Spawn Cape**. The cape lives in the offhand, lets the holder glide like they are wearing an elytra, and broadcasts their coordinates so players have a reason to hang around spawn.

## Behavior

- Only one cape can exist.
- It sits at `0 100 0` in `world` until someone picks it up.
- Pickup forces it into the offhand. If the offhand is occupied, that item is moved to an empty slot. No empty slot = no pickup.
- It cannot be moved, dropped, hoppered, or swapped. Logout, death, or leaving the allowed range returns it.
- Right-click boosts like a firework rocket. No rockets are consumed.
- +1 block and entity reach while it is in the offhand.
- Gliding is forced whenever the holder is in the air.
- Overworld: any axis outside `-1000` to `1000` returns it to spawn.
- Nether: any axis outside `-250` to `250` returns it.
- End and custom worlds: the player can enter, the cape immediately returns to overworld spawn.
- Death: the killer receives it if they have space, otherwise it returns to spawn.
- Logout: it returns to spawn.
- Ground item never despawns, is invulnerable, glowing, and named.
- Location is broadcast to chat, console, and an optional Discord webhook every 5 minutes.
- Lore is `12h 34m Player has worn me the longest`, updated when the current wearer loses it.

## Commands

| Command | Who | What |
|---|---|---|
| `/cape` | everyone | Current location, plus a hint for `/cape off` and `/cape help` |
| `/cape help` | everyone | How the cape works |
| `/cape off` | players | Hide broadcasts |
| `/cape on` | players | Show broadcasts again |
| `/cape reload` | ops | Reload `config.yml` |

## Config

Edit `plugins/SpawnCape/config.yml`:

- World names, return coordinates, axis limits
- Broadcast interval
- Reach bonus
- Discord webhook URL
- Item name / material / glow
- Messages (MiniMessage)

Wear time and mute list are stored in `plugins/SpawnCape/cape.yml`.

## Build

Requires Java 21+ (Paper 26.2 runs on Java 25) and Maven.

```bash
mvn -B package
```

The jar is written to `target/SpawnCape-1.0.0.jar`. Drop it in `plugins/` and restart.
