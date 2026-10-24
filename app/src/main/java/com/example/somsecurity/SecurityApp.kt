package com.example.somsecurity

import android.app.Application
import com.example.somsecurity.data.SecurityDatabase

class SecurityApp : Application() {

    val database: SecurityDatabase by lazy {
        SecurityDatabase.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: SecurityApp
            private set
    }
}

