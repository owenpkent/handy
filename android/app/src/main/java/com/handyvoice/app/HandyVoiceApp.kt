package com.handyvoice.app

import android.app.Application

class HandyVoiceApp : Application() {
    
    companion object {
        lateinit var instance: HandyVoiceApp
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
