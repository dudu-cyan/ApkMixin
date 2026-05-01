package com.dudu.test

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import com.dudu.mixin.lib.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

@Mixin(target = "com.tencent.qqnt.watch.mainframe.MainActivity")
object MainActivityMixin {
    @Shadow
    val mBase: Context? = null
    @Shadow
    fun getApplicationContext(): Context? {
        return mBase?.applicationContext
    }

    @JvmStatic
    @Inject(method = "onCreate(Landroid/os/Bundle;)V", at = At(AtValues.HEAD))
    fun onCreateHook() {
        println("MainActivity.hello() HEAD hooked!")
        val list = listOf("Banana", "Strawberry", "Apple")

        Toast.makeText(getApplicationContext(), "Hello From ApkMixin", Toast.LENGTH_LONG).show()

        runBlocking(Dispatchers.IO) {
            delay(300)
            Toast.makeText(getApplicationContext(), "Test Coroutines", Toast.LENGTH_LONG).show()
            delay(1000)
            list.forEach {
                Toast.makeText(getApplicationContext(), it, Toast.LENGTH_LONG).show()
                delay(500)
            }
        }
    }

}
