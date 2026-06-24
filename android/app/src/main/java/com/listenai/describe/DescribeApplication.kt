package com.listenai.describe

import android.app.Application

/**
 * App-level singleton holder. Currently empty — Day 5 wires up
 * llama.cpp engine bootstrap here (one warm engine instance shared
 * across activity launches so first synthesis isn't a cold boot).
 */
class DescribeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
