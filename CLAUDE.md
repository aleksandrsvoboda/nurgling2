# Repo topology

- `origin` (this repo, `alexandrelimassantana/nurgling2`) is a personal fork.
- The upstream project this was forked from is `aleksandrsvoboda/nurgling2`
  (https://github.com/aleksandrsvoboda/nurgling2). It is not configured as a
  git remote by default — add it when needed:
  `git remote add upstream https://github.com/aleksandrsvoboda/nurgling2`
- `mybase` is a personal integration branch: upstream/master plus a handful
  of local commits not (yet) upstreamed. It gets periodically rebased onto
  `upstream/master` to stay current; feature branches built on top of it
  (merge-base on one of mybase's own commits, not just shared
  upstream/master history) get rebased onto the new `mybase` afterward.
