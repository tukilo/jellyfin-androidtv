package org.jellyfin.androidtv

import android.app.Application
import org.jellyfin.androidtv.base.appModules
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin


//Just a workaround for now, remove me in the future.
fun startKoin(application: Application) {

	startKoin {
		androidContext(application)
		modules(listOf(appModules))
	}

}
