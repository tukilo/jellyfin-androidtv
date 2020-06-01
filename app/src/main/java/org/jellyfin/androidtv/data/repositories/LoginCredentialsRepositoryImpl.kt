package org.jellyfin.androidtv.data.repositories

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.TvApp
import org.jellyfin.androidtv.model.LogonCredentials
import org.jellyfin.androidtv.model.repository.SerializerRepository.serializer
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.apiclient.interaction.ApiClient
import org.jellyfin.apiclient.model.apiclient.ServerInfo
import org.jellyfin.apiclient.model.dto.UserDto
import timber.log.Timber
import java.io.IOException
import java.io.InputStream

class LoginCredentialsRepositoryImpl(
		private val context: Context,
		private val apiClient: ApiClient
) : LoginCredentialsRepository {

	private val CREDENTIALS_PATH = "org.jellyfin.androidtv.login.json"


	private val loginCredentials by lazy {
		GlobalScope.async(context = Dispatchers.IO) {
			try {
				val credsFile: InputStream = context.openFileInput(CREDENTIALS_PATH)
				val json = Utils.readStringFromStream(credsFile)
				credsFile.close()
				Timber.d("Saved credential JSON: %s", json)
				serializer.DeserializeFromString(json, LogonCredentials::class.java)
			} catch (e: IOException) {
				// none saved
				LogonCredentials(ServerInfo(), UserDto())
			} catch (e: Exception) {
				Timber.e(e, "Error interpreting saved login")
				LogonCredentials(ServerInfo(), UserDto())
			}
		}
	}

	override suspend fun getUserId(): String = loginCredentials.await().userDto.id

	override suspend fun saveLoginCredentials() {
		val credentials = LogonCredentials(apiClient.serverInfo, (context as TvApp).currentUser)
		withContext(Dispatchers.IO) {
			try {
				context.openFileOutput(CREDENTIALS_PATH, Context.MODE_PRIVATE).apply {
					write(serializer.SerializeToString(credentials).toByteArray())
					close()
				}
			} catch (ex: Exception) {
				Timber.e(ex, "Unable to save logon credentials")
			}
		}

	}


}
