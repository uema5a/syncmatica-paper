<p align="center">
  <img src="assets/icon.png" alt="SyncmaticaPaper" width="128">
</p>

# SyncmaticaPaper

SyncmaticaPaper brings [Syncmatica](https://github.com/sakura-ryoko/syncmatica) to Paper and Purpur
servers, so litematica schematics and their placements can be shared on the server. Players join with
the normal Litematica + Syncmatica mods — nothing extra is needed on their side. For Minecraft 26.1+.

### Notice — please use with caution

Syncmatica gives its users a lot of power and can have consequences for the server. Only use it if you
trust your players not to abuse it. If you'd rather lock it down, set `permissions.enforce: true` in the
config and grant the `syncmatica.*` nodes as you see fit.

## Setup

### Server

1. Requirements: a Paper or Purpur server for Minecraft 26.1+. Building the plugin needs JDK 25.
2. Build it with `./gradlew build` (on Windows, `gradlew.bat build`). The jar is written to
   `build/libs/syncmatica-paper-<version>.jar`.
3. Drop that jar into your server's `plugins/` folder and restart the server.
4. On first start it creates `plugins/SyncmaticaPaper/` with a `config.yml` and the folders it uses.

### Client

Install Fabric together with [litematica and malilib](https://masa.dy.fi/mcmods/client_mods/) and the
[Syncmatica](https://github.com/sakura-ryoko/syncmatica) mod, then join the server normally. That is all
that is required.

## Usage

On a server running SyncmaticaPaper you get a few extra buttons: two in the main menu let you see the
placements shared on the server and download them, and one in your schematic placement overview lets you
share your own litematic.

You need to be in the same dimension as a syncmatic to load it.

To modify a placement, unlock it on your client; lock it again afterwards to share the change with
everyone.

## Commands

All subcommands require the `syncmatica.command` permission (op by default).

| Command | Description |
| --- | --- |
| `/syncmatica list` | List the placements currently shared on the server. |
| `/syncmatica remove <id>` | Remove a shared placement (tab-completes the id). |
| `/syncmatica load` | Import every `.litematic` file dropped in `plugins/SyncmaticaPaper/syncmatics/` and share it at your position. |
| `/syncmatica reload` | Reload `config.yml`. |

## Configuration

`plugins/SyncmaticaPaper/config.yml`:

- **`quota`** — cap how many bytes each player may upload (`enabled`, `limit`). Off by default.
- **`permissions.enforce`** — when `true`, players need the `syncmatica.share`, `syncmatica.download`,
  `syncmatica.modify` and `syncmatica.remove` permissions to use those actions. When `false` (default)
  everyone may use them.
- **`debug.doPackageLogging`** — log every Syncmatica packet sent/received, for diagnostics. Off by default.

## License

Like the original Syncmatica, this is released under [CC0 1.0](LICENSE). Original mod by
[End-Tech](https://github.com/End-Tech/syncmatica) and
[sakura-ryoko](https://github.com/sakura-ryoko/syncmatica).
