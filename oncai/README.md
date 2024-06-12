# Onc.AI XNAT Maintenance Fork

## Updating From Upstream
The `main` branch is effectively `XNAT vX.X.X + Onc.AI changes`, and the `Onc.AI changes` are always
applied on top of a pristine XNAT source release.

> NOTE: We are not simply merging from upstream XNAT, as that would obscure the set of changes
> that we have made in this branch. Having a collection of always up-to-date patches against
> a current XNAT codebase will make the possibility of upstreaming changes much easier.

https://die-antwort.eu/techblog/2016-08-git-tricks-for-maintaining-a-long-lived-fork/

This reference outlines the various `git` commands you can use to keep a long-lived fork.
