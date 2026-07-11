# Repository Workflow for Coding Agents

These instructions apply to the entire repository. This repository is a fork of `altic-dev/FluidVoice`; local product development and upstream synchronization must remain separate.

## Branch Roles

- `main` is a clean, fast-forward-only mirror of `upstream/main`. Do not merge fork-specific work into it.
- `integration` is the base branch for the fork's combined, accepted changes.
- `feature/*`, `fix/*`, and `docs/*` contain one focused change and target `integration` in internal pull requests.
- `spike/*` branches are disposable experiments and should not be merged until intentionally converted into a focused change.
- `contrib/*` branches are clean, temporary branches based on `upstream/main` for potential upstream pull requests.

## Normal Change Workflow

1. Confirm the worktree is clean and identify any user-owned changes before editing.
2. Fetch `origin` and create a focused branch from `origin/integration`.
3. Keep unrelated ideas on separate branches and in separate pull requests.
4. Verify the change with the smallest relevant tests plus a build when appropriate.
5. Push the branch to `origin` and open an internal PR with `integration` as its base.

Example:

```bash
git fetch origin
git switch -c feature/my-feature origin/integration
# implement and verify
git push -u origin feature/my-feature
gh pr create --base integration --head feature/my-feature
```

Do not push feature work directly to `main` or `integration`. Do not retarget or open a PR against `altic-dev/FluidVoice` unless the user explicitly requests an upstream contribution.

## Parallel Work

Use one branch and one worktree per concurrent idea or agent. Do not let multiple agents modify the same worktree.

```bash
git worktree add ../FluidVoice-my-feature -b feature/my-feature integration
```

If one feature depends on another, make the dependency explicit. Prefer merging the prerequisite into `integration` before starting the dependent branch; use stacked branches only when waiting would block useful work.

## Upstream Synchronization

The `upstream` remote is `https://github.com/altic-dev/FluidVoice.git` and intentionally fetches only `refs/heads/main`. The upstream repository has branch names that differ only by letter case, which conflict on default case-insensitive macOS filesystems. Add it to a fresh clone with:

```bash
git remote add -t main upstream https://github.com/altic-dev/FluidVoice.git
```

Synchronizing `main` and merging it into `integration` is a maintenance exception to the normal no-direct-push rule and should be done only when explicitly requested:

```bash
git fetch upstream
git switch main
git merge --ff-only upstream/main
git push origin main
git switch integration
git merge main
git push origin integration
```

Never force-push `main` or `integration`. Resolve integration conflicts on a dedicated `sync/*` branch and merge them by PR when the update is not a clean merge.

## Preparing an Upstream Contribution

Never submit the long-lived `integration` branch upstream. Start from the current upstream branch and transfer only the focused change:

```bash
git fetch upstream
git switch -c contrib/my-feature upstream/main
git cherry-pick <commit>...
```

Clean up the commit series, verify it against upstream, and follow the upstream repository's contribution and PR templates before opening an upstream PR.
