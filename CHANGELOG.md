# Changelog

## Unreleased

- Fade in each lock screen glass surface when waking from AOD. Coordinate the clock material with the native ColorOS unlock transition.
- Fade in clock glass on wake and blend back to the native clock during unlock. Improve glyph contrast and add independent dark contour controls for the lock screen clock.
- Reworked lock screen glass to use native clock glyphs, keep the red OnePlus accent, and follow notification bounds as cards stack. Widget glass now stays attached to its card when the provider reloads.
- Apply clock refraction after ColorOS resolves digit colors, with a smooth bevel at high width settings. Replace stock PantaCard backdrops so weather and accessory widgets show the module's glass.
- Added separate development, beta and release build channels. GitHub Actions packages APKs and verification reports for each channel.
- A release tag on `main` prepares a signed GitHub release draft for review.

For published APKs and version notes, see [GitHub Releases](https://github.com/Kostubet/CPI-Kostubet-Edition/releases).
