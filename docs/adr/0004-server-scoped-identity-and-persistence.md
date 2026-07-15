# Scope identity and persistence to the server

Every server-dependent credential, request, media identity, cache entry, pending operation, and persisted record carries explicit server scope. This adds structure during the one-server First Viewing Loop, but avoids an expensive storage redesign for Multiple Servers and prevents credentials, private data, failures, or media identities from leaking or colliding across servers.
