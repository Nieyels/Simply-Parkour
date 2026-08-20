# Simply Parkour

Simply Parkour is a lightweight server-side Fabric mod for creating timed Minecraft parkour courses with pressure plates, checkpoints, and in-world leaderboards.

Players start a run by stepping on a configured start pressure plate. The timer is shown in the actionbar and stops when the player reaches the finish pressure plate. Best times are saved per player and remain after server restarts.

## Features

- Multiple named parkour courses
- Start and finish pressure plates
- Separate spawn location
- Optional checkpoint pressure plates
- Persistent best times
- Text Display leaderboards
- Top 10 scoreboard with personal rank support
- Actionbar timer in `mm:ss.mmm`
- Reset and checkpoint hotbar items
- Server-side timing with movement-based detection

## Installation

Install the mod on the Fabric server.

Clients do not need to install the mod.

## Commands

Set the main locations:

```text
/parkour set start <name>
/parkour set spawn <name>
/parkour set finish <name>
/parkour set scoreboard <name>
```

Add checkpoints and special fall zones:

```text
/parkour set checkpoint <name>
/parkour set fall <name> <blocks>
```

Open the editable config:

```text
/parkour config <name>
```

Manage checkpoints:

```text
/parkour checkpoint list <name>
/parkour checkpoint remove <name> <id>
```

Manage fall zones:

```text
/parkour fall list <name>
/parkour fall remove <name> <id>
```

Reload scoreboards:

```text
/parkour reloadscoreboard
/parkour reloadscoreboard <name>
```

## Gameplay

`Reset` stops the run and teleports the player back to the parkour spawn.

If a course has checkpoints, players also receive a `Checkpoint` item. This teleports them back to their latest checkpoint without stopping the run.

If checkpoints are configured, all checkpoints must be touched before the finish counts. Checkpoint order does not matter.

## License

MIT
