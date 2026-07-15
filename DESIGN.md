---
name: ChaiChai Emby Mobile
description: A quiet personal screening room for touch-first Android devices.
---

<!-- SEED: re-run $impeccable document once there's code to capture the actual tokens and components. -->

# Design System: ChaiChai Emby Mobile

## Overview

**Creative North Star: "The Quiet Screening Room"**

ChaiChai is cinematic, calm, and assured: media artwork leads, application chrome recedes, and familiar Android interaction keeps attention on choosing and watching. It combines Plex-like information density, Apple TV-like restraint, and YouTube-like interaction familiarity without imitating any one product.

The system adapts structurally as space grows. Compact windows prioritize one clear path; expanded windows add simultaneous context and useful density. Motion explains continuity during navigation and resize, never stages a performance.

**Key Characteristics:**

- Artwork-led, dark-first composition
- Restrained chrome and deliberate density
- Android-native interaction and accessible touch
- Continuity across resize, rotation, and fold changes
- Clear loading, failure, empty, and recovery states

## Colors

The palette uses neutral near-black architecture so artwork supplies most of the visible color, with muted honey-orange reserved for meaningful emphasis.

### Primary

- **Screening-room honey** (`[to be resolved during implementation]`): Primary actions, current selection, progress, and focus emphasis only.

### Neutral

- **Projector black** (`[to be resolved during implementation]`): Root background, kept chromatically neutral rather than warm-tinted.
- **Raised charcoal** (`[to be resolved during implementation]`): Navigation and structurally distinct surfaces.
- **Soft white** (`[to be resolved during implementation]`): Primary text and icons with accessible contrast.
- **Film gray** (`[to be resolved during implementation]`): Secondary text that still meets its required contrast.

**The Artwork Owns Color Rule.** The interface accent occupies no more than 10% of a typical screen; posters and backdrops provide the palette's breadth.

**The Semantic Accent Rule.** Honey marks action, selection, progress, or focus. It is never decorative filler.

## Typography

**Display Font:** `[humanist sans family to be chosen at implementation]`
**Body Font:** `[same humanist sans family to be chosen at implementation]`

**Character:** One Android-native-feeling humanist sans system keeps media titles confident, metadata calm, and controls legible under system font scaling. Typography uses Material roles rather than hand-tuned sizes per screen.

### Hierarchy

- **Display:** Used sparingly for featured media titles where artwork and available width support it.
- **Headline:** Screen and detail titles.
- **Title:** Shelf, section, and list-item titles.
- **Body:** Synopsis and supporting copy, limited to a readable measure where space permits.
- **Label:** Navigation, chips, actions, metadata, and control annotations; never forced into tiny uppercase tracking.

**The One Family Rule.** Hierarchy comes from Material roles, weight, and spacing—not competing display faces.

**The Scaled Text Rule.** Layout must survive Android system font scaling without clipping, inaccessible truncation, or lost actions.

## Elevation

The system is flat by default and separates structure through tonal surfaces, artwork treatment, and spacing. Elevation appears only where Android interaction requires a surface to pass over another, such as menus, transient controls, and sheets; exact tonal levels and shadows will be resolved during implementation.

**The Structural Elevation Rule.** Depth explains layering or interaction state and is never ambient decoration.

## Components

Component tokens will be captured after the first implementation pass. All eventual components must follow Material 3 interaction semantics, use touch targets of at least 48dp with adequate separation, expose complete TalkBack semantics, and support default, focused, pressed, disabled, loading, and error states where applicable.

Navigation must transform with available width: a compact navigation bar gives way to a rail or similarly space-appropriate structure, while destinations and journey state remain consistent. Media containers should privilege artwork and hierarchy rather than turning every item into a bordered or shadowed card.

Motion is responsive but restrained: quick state feedback, fade-through navigation, and shared artwork transitions may preserve context. Choreographed page entrances are prohibited. Reduced-motion mode uses crossfades or instant changes.

## Do's and Don'ts

### Do:

- **Do** keep media artwork as the visual protagonist and application chrome quiet.
- **Do** increase useful context and density as windows expand instead of stretching compact layouts.
- **Do** preserve selection, scroll, destination, and journey state through resize, rotation, split-screen, and fold changes.
- **Do** use familiar Android patterns, predictive Back, edge-to-edge insets, and touch targets of at least 48dp.
- **Do** meet WCAG AA and support TalkBack, large system text, sufficient contrast, and reduced motion.

### Don't:

- **Don't** use oversized promotional rows that subordinate the user's library, including Netflix-style promotional dominance.
- **Don't** create visually cluttered media dashboards.
- **Don't** let generic Material 3 decoration become colored cards everywhere.
- **Don't** stretch a phone composition across a tablet or leave compact bottom navigation untouched on expanded windows.
- **Don't** use decorative motion, glassmorphism, gradient text, over-rounded cards, or wide soft-shadow ghost cards.
