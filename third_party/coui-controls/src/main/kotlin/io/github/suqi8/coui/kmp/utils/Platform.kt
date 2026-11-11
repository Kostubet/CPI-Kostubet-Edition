// Copyright 2025, compose-coui-ui contributors
// SPDX-License-Identifier: Apache-2.0
package io.github.suqi8.coui.kmp.utils

enum class Platform {
    Android,
    IOS,
    Desktop,
    WasmJs,
    MacOS,
    Js,
}

fun platform(): Platform = Platform.Android
