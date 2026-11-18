# GlideTV contribution requirements

## Attribution

- Identify code implemented by Codex in the pull request description: "Implementation written by Codex at Harsha's request."
- Include this GitHub-recognized co-author trailer in commits containing Codex work:

  ```text
  Co-authored-by: Codex <199175422+chatgpt-codex-connector[bot]@users.noreply.github.com>
  ```

- Keep the authenticated PR opener's identity accurate. Co-authorship credits the implementation; it does not change who opened the PR.
- Preserve the co-author trailer when squashing commits.

## Visual review

- For any visible app change, attach actual before/after screenshots or a screen recording directly in the PR description before calling it ready for review.
- Capture the running app on a TV or emulator. Do not present generated mockups as implementation evidence.
- For focus, animation, or dynamic colors, show the relevant focus states and preferably a short recording.
- Identify device/emulator resolution, the tested commit, and any demo apps or sample data. Keep personal data out of captures.
- Inspect the captures for layout, clipping, readability, and remote navigation. Fix visible defects before requesting review.
- If runtime capture is blocked, clearly state the blocker and missing evidence in the PR; a passing build alone does not verify appearance.

## Validation

- Run checks appropriate to the change. For launcher UI, build the APK and check D-pad navigation in addition to visual evidence.
- Use `bash scripts/capture-ui.sh` only on a disposable emulator; it deliberately replaces the launcher's preferences with demo data.
