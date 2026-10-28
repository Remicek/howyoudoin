package com.devvalor.howyoudoing.app

import androidx.compose.ui.window.ComposeUIViewController

// Called from Swift as MainViewControllerKt.MainViewController(), named like the view controller it returns
@Suppress("FunctionNaming")
fun MainViewController() = ComposeUIViewController { App() }
