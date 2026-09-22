# livecontext (CLI)

One-command launcher for **LiveContext Community Edition**. It wraps the Docker
stack behind a single npm command. Docker is the runtime; this CLI only
orchestrates it (it does not replace Docker).

## Usage

```bash
npx livecontext@latest          # start (default): pulls images and boots the stack
npx livecontext@latest down     # stop and remove the containers
npx livecontext@latest logs     # follow the logs
npx livecontext@latest status   # container status
npx livecontext@latest update   # pull the latest images and restart
```

Then open **http://localhost:3000** and create the first account (it becomes the
admin). On the first run the CLI fetches the current model catalog so a fresh,
never-cloud-linked install ships with up-to-date models.

## Optional add-ons need the repository, not npx

Use the repository installation described in the [main README](https://github.com/livecontext-ai/livecontext-ce#quick-start) for optional extensions. The npm package omits the browser-agent source and SearXNG configuration required by that setup. Do not start a second installation over the same ports or container names; back up and plan a migration if switching installation methods.

## Requirements

- A machine supported by the release images. Prebuilt releases support x86-64; verify the release includes ARM64 before installing on Apple Silicon or other ARM hardware.
- Node.js LTS with npm (`node --version` and `npm --version`)
- Docker Engine 24+ with Compose v2 (or Docker Desktop 4.x and later)
- 4 GB RAM minimum, 8 GB recommended

Run management commands from the same directory each time. `./livecontext` contains the configuration and model seed; application data lives in Docker volumes, so deleting that folder does not reset the database.

```bash
cp livecontext/.env.example livecontext/.env
```

Edit `livecontext/.env`, then re-run `npx livecontext@latest`. Compose loads this file automatically. The launcher reports and checks the actual `FRONTEND_PORT` configured there. `LIVECONTEXT_PORT`, if already used in your environment, remains an override of that setting.

Use `LIVECONTEXT_HOME` to select a different configuration directory, and keep using that value for every management command. Back up the configuration, encryption keys and application volumes before `update`. `down` keeps volumes; the CLI does not erase application data. See [backup and recovery](https://github.com/livecontext-ai/livecontext-ce/blob/main/docker/README-CE.md#backup-and-recovery).

Licensed under the LiveContext Sustainable Use License 1.0. Part of https://github.com/livecontext-ai/livecontext-ce
