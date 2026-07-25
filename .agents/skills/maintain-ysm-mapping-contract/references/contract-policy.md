# Unreleased mapping contract

Without an explicit version instruction, keep these contracts at their current values:

- mod/release version
- `requests-v1.json` manifest schema
- settings and mappings document schemas
- fingerprint algorithm
- semantic definition revisions

Minecraft versions and dependency versions are not product contract versions. A named Minecraft version selects its branch. Dependencies remain fixed unless the requested feature, fix, compatibility work, or build repair requires the smallest viable update.

Breaking unreleased changes replace v1 in place. Keep cache atomic replacement, request ownership, digest invalidation, resolution safety, API behavior, documentation, and consumer resources aligned. Do not expose version-specific runtime names or private-derived graphs through public IDs, caches, reports, or distributions.
