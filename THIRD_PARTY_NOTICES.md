# Third-Party Notices

LiveContext Community Edition is licensed under AGPL-3.0 (see LICENSE). It
bundles and depends on third-party components that remain under their own
licenses, listed below. This file is informational and does not modify the
LICENSE terms.

## Bundled assets

| Component | Where | License | Notes |
|-----------|-------|---------|-------|
| Open Peeps (avatar artwork) | `frontend/public/avatars/*.svg` | CC0-1.0 (public domain) | By Pablo Stanley, https://www.openpeeps.com/ . No attribution required. |
| DiceBear (avatar composition) | avatar generation, authoring time | MIT | https://www.dicebear.com/ |
| Comfortaa (font) | `infra/keycloak/themes/livecontext/.../fonts/comfortaa-*.ttf` | SIL OFL 1.1 | Redistribution permitted; the OFL text must accompany the font files. |
| Inter (font) | `infra/keycloak/themes/livecontext/.../fonts/inter-latin.woff2` | SIL OFL 1.1 | Redistribution permitted; the OFL text must accompany the font files. |

## Notable runtime dependencies

Resolved through package managers (Maven, npm, pip) and not vendored into this
repository. Each keeps its own license; all listed here are permissive and
compatible with AGPL-3.0 distribution.

- Spring Boot and the Spring ecosystem - Apache-2.0
- Next.js and React - MIT
- PostgreSQL JDBC driver - BSD-2-Clause
- Flyway Community - Apache-2.0
- MinIO client SDK - Apache-2.0

## Integration catalog

The bundled integration catalog describes third-party APIs. Brand names and
icons referenced there are the trademarks of their respective owners and are
used for identification (interoperability) only.

## Pending (tracked, non-blocking)

- Ship the SIL OFL 1.1 license text alongside the bundled Comfortaa and Inter
  font files (OFL requires the license to accompany the fonts).
- Generate a full dependency SBOM with per-package licenses
  (`license-checker` for npm, the Maven license plugin) and fold the result into
  this file.
