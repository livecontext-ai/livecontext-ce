# Deployment templates

Ready-made entries for self-hosting platforms. Each one points at the repository's root
`docker-compose.yml` instead of restating it, so a change to the stack can never leave a
template describing something that no longer exists.

## Portainer

Add this URL as an app template in Portainer (**Settings > App Templates > URL**):

```
https://raw.githubusercontent.com/livecontext-ai/livecontext-ce/main/templates/portainer/livecontext-ce.json
```

LiveContext CE then appears under **App Templates**, with the ports, database password
and optional LLM keys exposed as form fields. Deploying it pulls this repository and
brings the stack up.

## Other platforms

For a platform that supports Docker Compose, point it at this repository's root
`docker-compose.yml`. Check the platform's Compose support and persistent-volume settings first.

Also read [server setup and backups](../docker/README-CE.md#server-and-reverse-proxy-setup). Keep persistent volumes when updating.

Three things to get right, whatever the platform:

1. **Publish both ports.** The web UI is on `FRONTEND_PORT` (3000) and the browser talks
   to the backend on `BACKEND_PORT` (8080). Both must be reachable from the machine you
   browse from.
2. **Route both services.** Without a proxy the app derives the backend origin from
   the address you opened and `BACKEND_PORT`. Behind a proxy, route the app and API
   to their respective services and forward WebSocket upgrades. A single-origin
   setup needs explicit API and WebSocket routes.

3. **Set public links and SMTP.** Set `PUBLIC_BASE_URL` to the frontend URL and `GATEWAY_PUBLIC_URL` to the backend URL, without trailing slashes, for OAuth callbacks and email links. For internet access use HTTPS and forward WebSocket upgrades. Configure SMTP before relying on password resets or invitations.

The database, Redis and object storage persist in Docker volumes across updates.
