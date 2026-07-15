# Use a feature-first modular monolith

The Mobile Client is a feature-first modular monolith: capability modules depend on narrow contracts rather than one another, while focused platform modules own Emby integration, persistence, playback, adaptive navigation, and the design system. This rejects both an undivided application module and a premature plugin or fine-grained architecture, preserving testable dependency direction and incremental delivery while allowing shared abstractions only after real consumers demonstrate a stable concept.
