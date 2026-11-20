# Operational boundaries

The original local operator log is not part of the public repository. Local Android development uses a dedicated emulator only; this history does not grant anyone access to devices, signing keys, personal photos, model acquisitions or publication credentials. The application source and specifications do not include a production release or authorize external sharing.

## Atelier integration authority

The operator selected [Atelier](ATELIER.md) and authorized its specifications, design kit, interim icon and real Android implementation. The canonical development/integration base is now sanitized `main` on `lrq3000/PristinePage`; earlier private development refs and the prior APKs/evidence remain local and are not sources of publication ancestry. Fork each coherent implementation branch from the current clean `main` in isolated storage. After a stable, verified boundary, make a linear `main` squash with a self-contained intention, retaining the exact sanitized feature history on GitHub as `archive/<feature-or-bugfix-branch>`. Verify both exact new tips, expected destination and public-only ancestry before publishing either ref. No force, tag push, cleanup, publication of archives from private ancestry, store upload or change to unrelated GitHub settings.

Public evidence uses synthetic or specifically cleared data only. Model acquisition/runtime rights, approved device readiness, development signing and sensitive original inputs are separately gated; keep any device identifiers, private paths, operator logs and local evidence out of the public branch. Missing device or model readiness must be recorded as a block on the affected acceptance, not replaced with a simulated success.

## Carnet continuation

The operator authorized a public, content-identical Atelier snapshot on `lrq3000/PristinePage:feat/atelier-contract` using public ancestry and Git identity, followed by [Carnet](../design/carnet/README.md) from that exact snapshot. Unsanitized source refs are retained only under local `archive/` names in the public development clone. Do not publish these refs, even when unrelated to the current branch. Use explicit push refs, a public Git identity, and a local outbound-history check; never infer consent to publish from branch naming. This exception neither advances `main` nor authorizes pushing Carnet, signing, devices, model acquisition, deletions or changes in the separately frozen private checkout.
