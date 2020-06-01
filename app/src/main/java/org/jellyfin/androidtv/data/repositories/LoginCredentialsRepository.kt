package org.jellyfin.androidtv.data.repositories

interface LoginCredentialsRepository {
	suspend fun getUserId() : String
	suspend fun saveLoginCredentials()
}
