# Security Policy

## Reporting a vulnerability

Please report suspected vulnerabilities **privately** to **security@livecontext.ai**.
Do not open a public GitHub issue for security reports.

Include what you can:

- A description of the issue and its impact
- Steps to reproduce (a minimal proof of concept helps a lot)
- The affected version / commit and your deployment mode (Docker monolith, source build)

We will acknowledge your report within 5 business days, keep you informed of
progress, and credit you in the release notes if you wish.

## Scope

This policy covers the code in this repository (the Community Edition).
For the hosted service at livecontext.ai, use the same contact address.

## Supported versions

Security fixes land on the latest published release of the Community Edition.
Older snapshots are not patched retroactively - please upgrade to the most
recent release before reporting.

## Good practices for self-hosters

- Keep your LLM provider keys and database credentials out of the repo: use
  `docker/.env.ce` as described in `docker/README-CE.md` (never commit it).
- **Change the default credentials before exposing the deployment beyond
  localhost.** The compose ships local defaults (`POSTGRES_PASSWORD=postgres`,
  `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD=minioadmin`) for a zero-config first run -
  override them in your `.env` for any networked deployment.
- Run the stack behind TLS (reverse proxy) if you expose it beyond localhost, and
  do not publish the backend port (`8080`, which also serves `/actuator/health`)
  directly to untrusted networks.
- **The code/transform workflow node executes user-supplied code on the host
  process, not in a sandbox.** Treat anyone who can build a workflow as trusted to
  run code in the container. Do **not** expose a multi-tenant CE instance to
  untrusted users without isolating it (dedicated container/VM, network egress
  limits); a future release will add an opt-out flag to disable code nodes.
- Back up the PostgreSQL volume before upgrading.
