# Make the playback service authoritative

One Media3 `MediaSessionService` owns active playback so playback, progress reporting, audio focus, notifications, and system controls survive activity recreation and Adaptive Layout changes. Features submit stable playback intent to a coordinator, which negotiates with Emby and creates an internal playback plan; features do not construct Media3 items, authenticated URLs, headers, or transcoding parameters, accepting a more explicit boundary in exchange for lifecycle continuity and centralized playback policy.
