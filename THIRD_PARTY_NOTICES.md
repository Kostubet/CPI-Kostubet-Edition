# Acknowledgements

PixelLauncherEnhanced by Mahmud0808: https://github.com/Mahmud0808/PixelLauncherEnhanced

Thank you to PixelLauncherEnhanced / Mahmud0808 for permission to use the files.

The ClipIconView floating-background integration was adapted from the FloatingGlassBackground approach in IconGlass.kt (GPL-3.0). CherryPick uses its own material renderer and ColorOS-specific source tracking. A copy of the upstream file, its license and source revision are in third_party/PixelLauncherEnhanced. The full local reference checkout is excluded from source distribution.

Thanks to @anime_killer2009 for substantial help: https://t.me/anime_killer2009

Thanks to Kostubet Topics for their help: https://t.me/KostubetTopics

LuckyTool by luckyzyx (GPL-3.0) informed the launcher sizing integration. CPI applies a percentage to the size resolved for the individual native icon view. [LuckyTool launcher sizing](https://github.com/luckyzyx/LuckyTool/blob/90af6f61c0b31dff3d1fb14b53d2d935c3c31654/app/src/main/java/com/luckyzyx/luckytool/hook/scopes/launcher/HookOplusBubbleTextView.kt#L51)

ONextBox 17.0.0.100 by MiToverG422 and contributors (GPL-3.0). See third_party/ONextBox-NOTICE.md for upstream source and modifications.

COUI v2.0.0 by suqi8 and compose-coui-ui contributors (Apache-2.0) supplies the settings application controls. CPI uses the published Android artifacts for UI, Preference and Icons. The license and attribution are included in the APK under `assets/licenses/`. [Upstream release sources](https://github.com/suqi8/coui/tree/v2.0.0)

The iOS 27 app transitions adapt the AppGenieMotion and AppWindowDeformation code from CherryPick Icons 0.3.20-dev by immorgue. CPI uses its own ColorOS launcher bindings and animation speed settings. Project: https://github.com/immorgue/CherryPick-Icons

AndroidLiquidGlass / Backdrop by Kyant (Copyright 2025 Kyant, Apache-2.0) provides optics for shade materials and CPI’s optional glass engine. CPI adapts the rounded-rectangle distance field, circular refraction profile and directional highlight from `backdrop/internal/Shaders.kt` at upstream revision `eb4a0ea2e17542ea28c2e71088f7dd9acf77aad5`. CPI uses Android View and RenderNode, its own capture adapter and a three-sample chromatic effect. The license is included in the APK at `assets/licenses/AndroidLiquidGlass-LICENSE.txt`. [Upstream source](https://github.com/Kyant0/AndroidLiquidGlass)
