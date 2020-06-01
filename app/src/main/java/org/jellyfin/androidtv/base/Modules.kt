
package org.jellyfin.androidtv.base

import org.jellyfin.androidtv.TvApp
import org.jellyfin.androidtv.data.repositories.LoginCredentialsRepository
import org.jellyfin.androidtv.data.repositories.LoginCredentialsRepositoryImpl
import org.jellyfin.androidtv.preferences.UserPreferences
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModules = module {
	single { UserPreferences(androidContext()) }

	single { (androidApplication() as TvApp).apiClient }

	single { LoginCredentialsRepositoryImpl(get(), get()) as LoginCredentialsRepository}
}
