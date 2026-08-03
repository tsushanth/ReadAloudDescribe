package com.listenai.describe

import android.app.Application
import android.content.Context
import com.listenai.describe.engine.LlamaEngineController

/**
 * App-level singleton holder. Owns the one warm [LlamaEngineController]
 * instance shared across the share-target Activity and the
 * accessibility service, so neither has to reload weights on its own
 * and both serialize through the same native handle.
 */
class DescribeApplication : Application() {

    lateinit var engineController: LlamaEngineController
        private set

    override fun onCreate() {
        super.onCreate()
        engineController = LlamaEngineController()
    }

    companion object {
        fun engineController(context: Context): LlamaEngineController =
            (context.applicationContext as DescribeApplication).engineController
    }
}
