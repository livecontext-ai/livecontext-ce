# Contributing to LiveContext Community Edition

Thanks for your interest in improving LiveContext CE. This guide covers how to
build, test, and submit changes.

## Getting started

The fastest way to run CE is the prebuilt stack:

```bash
docker compose up -d
```

To build from source you need JDK 21, Maven, and Node.js 20+. The backend is a
single Spring Boot monolith built with the `ce` Maven profile:

```bash
cd backend && mvn -Pce -pl monolith-service -am package
cd ../frontend && npm install && npm run build
```

## Development flow

1. Open an issue (or comment on an existing one) before large changes, so we can
   agree on the approach first.
2. Keep changes focused: one logical change per pull request.
3. Add tests. Every bug fix needs a regression test that fails before the fix and
   passes after it. Backend: `cd backend/<module> && mvn test`. Frontend:
   `cd frontend && npm test`.
4. Match the surrounding code. Follow the existing patterns rather than adding new
   abstractions or parallel code paths.

## Code style

- All code and comments must be in English.
- User-facing text uses next-intl; add every new key to all locale files under
  `frontend/messages/` with a real translation (English is the reference).
- Reuse the shared API clients and helpers rather than calling the backend
  directly from the frontend.

## Sign your commits (DCO)

We use the Developer Certificate of Origin (https://developercertificate.org). By
signing off you certify that you wrote the change, or otherwise have the right to
submit it under the project license. Add a sign-off line to every commit:

```bash
git commit -s -m "your message"
```

This appends a `Signed-off-by: Your Name <you@example.com>` line using your git
identity.

## License of contributions

LiveContext CE is licensed under the LiveContext Sustainable Use License 1.0
(see LICENSE). By contributing, you agree that your contributions are provided
under that same license.

## Reporting security issues

Please do not open a public issue for a vulnerability. See SECURITY.md for how to
report one privately.
