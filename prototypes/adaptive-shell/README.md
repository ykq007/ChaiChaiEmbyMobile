# Adaptive shell prototype

Throwaway UI prototype for the Wayfinder ticket “Prototype the adaptive app shell and browsing experience across phones, tablets, and foldables.” It compares three structural directions on one route:

- `A` — Spotlight shelves: cinematic Home with conventional shelves and expanded detail context.
- `B` — Library workbench: denser, persistent context and explicit partial-state visibility.
- `C` — Continuity canvas: calmer top navigation and a vertically composed media feed.

Run from the repository root:

```sh
python3 -m http.server 4173 --directory prototypes/adaptive-shell
```

Then open `http://localhost:4173/?variant=A&width=expanded&view=home`. Use the fixed bottom switcher or the left/right arrow keys to compare variants. The controls above the preview change the representative window width and surface; all selections persist in the URL.

This is read-only, contains no production architecture, and should be deleted after the design answer is captured in the issue.
