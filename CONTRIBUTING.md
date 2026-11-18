# Contributing to GlideTV

GlideTV is a lightweight, native Android TV launcher. Contributions from outside the project are welcome: bug fixes, accessibility, documentation, translations and scene packs.

## Report a bug

[Open an issue](https://github.com/ghanta1996/GlideTV/issues/new) with:

- TV model, Android/Google TV version and Glide version.
- Steps to reproduce, expected behavior and what happened instead.
- A screenshot or short recording for display/focus problems, with private information removed.
- Relevant logs, if available. Remove tokens, pairing codes, network addresses and viewing history.

For a feature or larger redesign, start an issue explaining the user need and proposed behavior so scope can be discussed before implementation.

## Make a change

```mermaid
flowchart TD
A["Fork and branch from main"] --> B["Build and make your change"]
B --> C["Run relevant checks"]
C --> D["Open a PR against main"]
```

Use the [developer guide](docs/DEVELOPMENT.md) for local builds. Keep changes focused and preserve D-pad navigation, readable TV text and existing preferences. In the PR, explain the result, link related issues and list checks you ran.

## Show visible changes

Attach **before/after screenshots or a short screen recording in the PR description**. Capture the running app on an emulator or TV; mockups are useful for proposals but do not verify an implementation.

Show relevant focus states, transitions and empty states. Include the tested commit, Android version, screen resolution and whether demo data was used. For launcher interactions, verify remote/D-pad navigation as well as appearance. See [capture instructions](docs/DEVELOPMENT.md#visual-review-captures).

## Validation reference

| Change | Useful checks |
| --- | --- |
| Native launcher | `bash build.sh`, relevant runtime checks on Android TV |
| Icon colors | `bash scripts/test-icon-palette.sh` |
| APK download handling | `bash scripts/test-downloads.sh` |
| Scene packs and release tooling | `python3 scripts/test_release.py` |
| Documentation | Check links and preview diagrams/images |

If you could not run a check, say so and explain the limitation. Do not describe an untested result as verified.

## Scene contributions

Follow the [scene pack guide](scenes/README.md). Include artwork provenance and permission to redistribute it, keep packs small, and update the scene changelog. Never replace the contents of an already published pack version.

## Attribution and review

Disclose substantive AI-generated implementation in the PR description and preserve co-author trailers when squashing. Codex-specific attribution and visual-review requirements are in [AGENTS.md](AGENTS.md). A co-author credit does not require collaborator access.

Maintainers review and merge changes and handle official releases. Development APKs use a different signing key and cannot replace the official app in place. Contributions are provided under the repository's [MIT license](LICENSE); do not submit third-party code or artwork without a compatible license.
