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

## Contributor License Agreement (CLA)

Before your first pull request can be merged, you sign the
[LiveContext Contributor License Agreement](CLA.md). You sign it once, with
your GitHub account, and it covers all your future contributions.

To sign, post this comment on your first pull request, with your GitHub account:

```
I have read the LiveContext Contributor License Agreement version 1.0 (CLA.md) and I agree to it.
```

We check for it before merging. You sign once per version of the agreement. If
your employer (or a client) has rights in what you write, see section 4 of the
agreement; to sign on behalf of a company, see section 7 or write to
oss@livecontext.ai.

## License of contributions

LiveContext CE is licensed under the LiveContext Sustainable Use License 1.1,
see [LICENSE](LICENSE) and the plain-language [licensing FAQ](LICENSING.md).
You keep whatever rights you hold in your contributions. The CLA grants
LiveContext a license to them that also allows distributing them under other
terms (commercial editions, or a future change of the project license), which
the Sustainable Use License alone would not allow.

## Reporting security issues

Please do not open a public issue for a vulnerability. See SECURITY.md for how to
report one privately.
