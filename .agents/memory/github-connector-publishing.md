---
name: GitHub connector publishing
description: Environment-specific guidance for publishing repository changes when the local Git credential helper cannot authenticate.
---

When the local Git HTTPS credential helper rejects a push but the Replit-managed GitHub connection is authorized, use the connector API for the user-approved publish instead of requesting or handling a personal access token.

**Why:** The local remote can remain configured correctly while its stored HTTPS credential is invalid; the managed connection can still perform authenticated repository operations without exposing credentials.

**How to apply:** Read the remote branch first, require the expected base to match, create the final tree/commit through the connector, update the branch with `force: false`, and verify the remote ref and important files afterward. An API push may leave the workspace's local remote-tracking ref stale, so report that distinction clearly.