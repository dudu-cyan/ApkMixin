package com.dudu.test

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.material3.Text
import com.dudu.mixin.lib.*
import com.tencent.qqnt.watch.mainframe.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking


@Mixin(target = "com.tencent.qqnt.watch.mainframe.MainActivity")
object MainActivityMixin {

    @JvmStatic
    @Inject(method = "onCreate(Landroid/os/Bundle;)V", at = At(AtValues.TAIL))
    fun onCreateHook(activity: ComponentActivity, savedInstanceState: Bundle?) {
        println(">>> onCreate: activity=$activity bundle=$savedInstanceState")

        Toast.makeText(activity, "Hello From ApkMixin", Toast.LENGTH_LONG).show()

        activity.setContent {
            Text("Hello")
        }
/*
        runBlocking(Dispatchers.IO) {
            delay(300)
            launch(Dispatchers.Main) {
                Toast.makeText(activity, "Test Coroutines", Toast.LENGTH_LONG).show()
            }
            delay(1000)
            listOf("Banana", "Strawberry", "Apple").forEach {
                launch(Dispatchers.Main) {
                    Toast.makeText(activity, it, Toast.LENGTH_LONG).show()
                }
                delay(500)
            }
        }*/
    }


}
