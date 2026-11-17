# ONextBox UI motion

The CPI View implementations in `OnextMotion.kt` and `ColorosControls.kt` adapt ONextBox by MiToverG422 and contributors, licensed under GPL-3.0. `OnextSliderSpotlight.kt` contains the upstream slider spotlight AGSL.

Upstream revision: `5437fcf80dc95657451866a04036b118b2c87701` (V17.0.0.100).
Upstream: https://github.com/MiToverG422/ONextBox/tree/5437fcf80dc95657451866a04036b118b2c87701

Source components: ColorOsTopBarMotion, ColorOsDialogButtonMotion, DampedDragAnimation, LiquidGlassBottomNavigationBar, ColorOsPopup, ColorOsSwitch, ColorOs17SettingsSlider, and ColorOsSpotlightRenderer. CPI adapts their springs, touch interaction, geometry, and slider lighting for Android Views and its own settings and backdrop renderer. See COPYING for the full license.


For 0.2.82, the UI additionally adapts ColorOs17BottomNavigationBar (the standard capsule), the ColorOsTopBarMaterialView fallback surface and edge, HomeHeroCard, HomeRuntimeStatusCards, HomeInfoCard region detection, and the AboutAppCard / AboutAuthorGroup layouts. CPI retains its own branding, data sources and settings.


For 0.2.85, OnextShadowEdge.kt includes the upstream ShadowEdge AGSL from ColorOsPopupMaterialView. ColorosMaterialEdges.kt adapts the corresponding popup uniforms, the dialog material fallback edge, and the spotlight treatment for CPI's View hierarchy.

Build 334 uses COUI sources supplied in the CPI archive, under `third_party/coui`. COUI is by suqi8 and contributors and is distributed under the Apache License 2.0. The Android modules use the CPI build configuration.

`ui/onextbox` contains the theme, switch, slider, toolbar button and blur, and confirmation dialog sources from ONextBox revision `64ad446594d5d864537b90a03864aafa4b59e822`. CPI adapts their package names and hosts these controls in its settings screens. The slider retains CPI's hue track and horizontal stretch.

Build 336 also adapts SettingsSwitchPreference and ColorOsSettingsSliderRow from that revision and uses the SettingsExpandArrow and SettingsPopupArrow drawings from SettingsComponents.

Upstream: https://github.com/MiToverG422/ONextBox/tree/64ad446594d5d864537b90a03864aafa4b59e822


Build 339 imports the ready `ColorOsPopup`, `ColorOsDropdownPreference`, `ColorOsSearchSurface`,
`ColorOs17BottomNavigationBar`, `ColorOsBottomBarMaterialView`, `SettingsComponents`,
`SettingsPreferenceRows`, `SettingsInteraction`, slider-row and feature-expansion code.
The source is ONextBox revision `5e417d77614b12e4b12c1cb882b8c539d8fdb471` (files unchanged
from the checked-out release were also verified against this revision). Package imports,
application-specific search highlighting and unused Material opt-ins are adapted for CPI;
CPI supplies its own preference bindings and retains its page arrangement.
The previous View popup/search implementations and unused connected-action controls were removed.
HSV color tracks use the supplied COUI `HsvHueSlider`, `HsvSaturationSlider`, `HsvValueSlider`.
No Kyant Backdrop dependency is used for this application UI.

Build 343 ports AboutAppCard and BuildTypeBadge from the supplied ONextBox SettingsMainPage to CouiAboutPage. CPI substitutes its branding, Open-Beta label and existing actions. The ready SettingsGroup, SettingsCardRow, SettingsDivider and SettingsSwitchPreference are reused. Root navigation retains the upstream native controls; its adapter supplies the requested detachment outline.

Build 345 restores the upstream materials of ColorOs17BottomNavigationBar and
ColorOsTopBarButton, removing CPI's shared NavigationMaterial and custom connected
outline. CPI retains its persistent navigation host, hit targets, reveal sizing and
unclipped spring motion. The bottom-bar material is initialized synchronously when
attached and sized, avoiding a deferred change from fallback to native appearance.
