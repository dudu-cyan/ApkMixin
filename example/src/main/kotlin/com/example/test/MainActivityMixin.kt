package com.example.test

import com.example.mixin.lib.Mixin
import com.example.mixin.lib.Inject
import com.example.mixin.lib.At
import com.example.mixin.lib.AtValues
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Mixin(target = "com.example.test.MainActivity")
object MainActivityMixin {
    @JvmStatic
    @Inject(method = "hello", at = At(AtValues.HEAD))
    fun onHelloHook(activity: Any) {
        println("MainActivity.hello() HEAD hooked!")
        
        if (activity is ComponentActivity) {
            activity.setContent {
                MyComposeUI()
            }
            println("Called setContent directly!")
        } else {
            println("Activity is not a ComponentActivity, type: ${activity.javaClass.name}")
        }
    }

    @Composable
    fun MyComposeUI() {
        Text(text = "Hello from Direct Compose Mixin!")
    }
}
