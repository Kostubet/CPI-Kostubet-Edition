# ONextBox UI motion

The CPI View implementations in `OnextMotion.kt` and `ColorosControls.kt` adapt ONextBox by MiToverG422 and contributors, licensed under GPL-3.0. `OnextSliderSpotlight.kt` contains the upstream slider spotlight AGSL.

Upstream revision: `5437fcf80dc95657451866a04036b118b2c87701` (V17.0.0.100).
Upstream: https://github.com/MiToverG422/ONextBox/tree/5437fcf80dc95657451866a04036b118b2c87701

Source components: ColorOsTopBarMotion, ColorOsDialogButtonMotion, DampedDragAnimation, LiquidGlassBottomNavigationBar, ColorOsPopup, ColorOsSwitch, ColorOs17SettingsSlider, and ColorOsSpotlightRenderer. CPI adapts their springs, touch interaction, geometry, and slider lighting for Android Views and its own settings and backdrop renderer. See COPYING for the full license.

For 0.2.82, the UI additionally adapts ColorOs17BottomNavigationBar (the standard capsule), the ColorOsTopBarMaterialView fallback surface and edge, HomeHeroCard, HomeRuntimeStatusCards, HomeInfoCard region detection, and the AboutAppCard / AboutAuthorGroup layouts. CPI retains its own branding, data sources and settings.

For 0.2.85, OnextShadowEdge.kt includes the upstream ShadowEdge AGSL from ColorOsPopupMaterialView. ColorosMaterialEdges.kt adapts the corresponding popup uniforms, the dialog material fallback edge, and the spotlight treatment for CPI's View hierarchy.
