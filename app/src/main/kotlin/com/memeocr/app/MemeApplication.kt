package com.memeocr.app

import android.app.Application
import com.memeocr.app.data.AppDatabase
import com.memeocr.app.worker.IndexControl

class MemeApplication : Application() {
    val database by lazy { AppDatabase.create(this) }
    val control by lazy { IndexControl(this) }
}

