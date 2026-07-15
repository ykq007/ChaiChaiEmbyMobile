# Touch playback prototype

Throwaway UI prototype for “Prototype touch-first playback across phones, tablets, and foldables.” Three structural directions are switchable with `?variant=`:

- `A` — Familiar cinema: centered transport, bottom timeline, compact top chrome.
- `B` — Thumb zones: transport clustered near the lower corners with a persistent context strip.
- `C` — Control dock: a bottom dock on phones and a side dock on expanded windows.

Run from the repository root:

```sh
python3 -m http.server 4174 --directory prototypes/touch-playback
```

Open `http://localhost:4174/?variant=A&width=phone&state=playing`. Compare variants with the fixed switcher or Left/Right keys. Use the lab controls to exercise window width, paused/playing state, track selection, and the later danmaku affordance.

This is read-only throwaway code, not production architecture. It uses abstract artwork and simulated state only.
