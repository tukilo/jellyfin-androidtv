package org.jellyfin.androidtv.ui.home

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.util.ArrayMap
import androidx.leanback.widget.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.TvApp
import org.jellyfin.androidtv.base.CustomMessage
import org.jellyfin.androidtv.browsing.BrowseRowDef
import org.jellyfin.androidtv.browsing.IRowLoader
import org.jellyfin.androidtv.browsing.StdBrowseFragment
import org.jellyfin.androidtv.channels.ChannelManager
import org.jellyfin.androidtv.constants.HomeSectionType
import org.jellyfin.androidtv.data.repositories.LoginCredentialsRepository
import org.jellyfin.androidtv.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.livetv.LiveTvGuideActivity
import org.jellyfin.androidtv.model.ChangeTriggerType
import org.jellyfin.androidtv.playback.AudioEventListener
import org.jellyfin.androidtv.playback.MediaManager
import org.jellyfin.androidtv.preferences.enums.AudioBehavior
import org.jellyfin.androidtv.presentation.CardPresenter
import org.jellyfin.androidtv.presentation.PositionableListRowPresenter
import org.jellyfin.androidtv.querying.QueryType
import org.jellyfin.androidtv.querying.StdItemQuery
import org.jellyfin.androidtv.querying.ViewQuery
import org.jellyfin.apiclient.interaction.Response
import org.jellyfin.apiclient.model.entities.DisplayPreferences
import org.jellyfin.apiclient.model.entities.LocationType
import org.jellyfin.apiclient.model.entities.MediaType
import org.jellyfin.apiclient.model.entities.SortOrder
import org.jellyfin.apiclient.model.livetv.RecommendedProgramQuery
import org.jellyfin.apiclient.model.livetv.RecordingQuery
import org.jellyfin.apiclient.model.querying.*
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.*
import java.util.regex.Pattern

class HomeFragment : StdBrowseFragment() {

	private val loginCredentialsRepository by inject<LoginCredentialsRepository>()

	private val rows: MutableList<HomeFragmentRow> = ArrayList()
	private var views: ItemsResult? = null

	private val nowPlaying: HomeFragmentNowPlayingRow by lazy { HomeFragmentNowPlayingRow(requireContext()) }
	private val liveTVRow: HomeFragmentLiveTVRow by lazy { HomeFragmentLiveTVRow(requireContext()) }
	private val footer: HomeFragmentFooterRow by lazy { HomeFragmentFooterRow(requireContext()) }

	// Init leanback home channels;
	private var channelManager: ChannelManager = ChannelManager()

	private val audioEventListener: AudioEventListener = object : AudioEventListener() {
		override fun onQueueStatusChanged(hasQueue: Boolean) {
			nowPlaying.update(mRowsAdapter)
		}
	}

	override fun onActivityCreated(savedInstanceState: Bundle?) {
		MainTitle = this.getString(R.string.home_title)
		super.onActivityCreated(savedInstanceState)

		CoroutineScope(Dispatchers.IO).launch {
			loginCredentialsRepository.saveLoginCredentials()
		}

		// Get auto bitrate
		TvApp.getApplication().determineAutoBitrate()

		//First time audio message
		if (!mApplication.systemPreferences.audioWarned) {
			mApplication.systemPreferences.audioWarned = true
			AlertDialog.Builder(mActivity)
					.setTitle(mApplication.getString(R.string.lbl_audio_capabilitites))
					.setMessage(mApplication.getString(R.string.msg_audio_warning))
					.setPositiveButton(mApplication.getString(R.string.btn_got_it), null)
					.setNegativeButton(mApplication.getString(R.string.btn_set_compatible_audio)) { _, _ -> mApplication.userPreferences.audioBehaviour = AudioBehavior.DOWNMIX_TO_STEREO }
					.setCancelable(false)
					.show()
		}

		//Subscribe to Audio messages
		MediaManager.addAudioEventListener(audioEventListener)

		// Setup activity messages
		mActivity.registerMessageListener { message ->
			if (message == CustomMessage.RefreshRows) {
				if (hasResumeRow()) {
					refreshRows()
				}
			}
		}
		if (mApplication.userPreferences.liveTvMode) {
			// Open guide activity and tell it to start last channel
			val guide = Intent(activity, LiveTvGuideActivity::class.java)
			guide.putExtra("loadLast", true)
			startActivity(guide)
		}

	}

	override fun onResume() {
		super.onResume()

		// Update leanback channels
		channelManager.update()

		//make sure rows have had a chance to be created
		Handler().postDelayed({ nowPlaying.update(mRowsAdapter) }, 750)
	}

	override fun onDestroy() {
		super.onDestroy()
		MediaManager.removeAudioEventListener(audioEventListener)
	}

	override fun setupEventListeners() {
		super.setupEventListeners()
		mClickedListener.registerListener { itemViewHolder: Presenter.ViewHolder?, item: Any?, rowViewHolder: RowPresenter.ViewHolder?, row: Row? ->
			liveTVRow.onItemClicked(itemViewHolder, item, rowViewHolder, row)
			footer.onItemClicked(itemViewHolder, item, rowViewHolder, row)
		}
	}

	fun addSection(type: HomeSectionType?) {
		when (type) {
			HomeSectionType.LATEST_MEDIA -> rows.add(loadRecentlyAdded())
			HomeSectionType.LIBRARY_TILES_SMALL -> rows.add(loadLibraryTiles())
			HomeSectionType.LIBRARY_BUTTONS -> rows.add(loadLibraryButtons())
			HomeSectionType.RESUME -> rows.add(loadResumeVideo())
			HomeSectionType.RESUME_AUDIO -> rows.add(loadResumeAudio())
			HomeSectionType.ACTIVE_RECORDINGS -> rows.add(loadLatestLiveTvRecordings())
			HomeSectionType.NEXT_UP -> rows.add(loadNextUp())
			HomeSectionType.LIVE_TV -> if (TvApp.getApplication().currentUser.policy.enableLiveTvAccess) {
				rows.add(liveTVRow)
				rows.add(loadOnNow())
			}
		}
	}

	override fun setupQueries(rowLoader: IRowLoader) {
		val application = TvApp.getApplication()

		// Update the views before creating rows
		application.apiClient.GetUserViews(application.currentUser.id, object : Response<ItemsResult?>() {
			override fun onResponse(response: ItemsResult?) {
				views = response

				// Use "emby" as app because jellyfin-web version uses the same
				TvApp.getApplication().getDisplayPrefsAsync("usersettings", "emby", object : Response<DisplayPreferences>() {
					override fun onResponse(response: DisplayPreferences) {
						val prefs = response.customPrefs

						// Section key pattern
						val pattern = Pattern.compile("^homesection(\\d+)$")

						// Add sections to map first to make sure they stay in the correct order
						val sections = ArrayMap<Int, HomeSectionType>()

						// Set defaults
						for (i in DEFAULT_SECTIONS.indices) {
							sections[i] = DEFAULT_SECTIONS[i]
						}

						// Overwrite with user-preferred
						for (key in prefs.keys) {
							val matcher = pattern.matcher(key)
							if (!matcher.matches()) continue
							val index = matcher.group(1).toInt()
							val sectionType = HomeSectionType.getByName(prefs[key])
							if (sectionType != null) sections[index] = sectionType
						}

						// Fallback when no customization is done by the user
						rows.clear()

						// Actually add the sections
						for (section in sections.values) {
							if (section != HomeSectionType.NONE) addSection(section)
						}
						loadRows()
					}

					override fun onError(exception: Exception) {
						Timber.e(exception, "Unable to retrieve home sections")

						// Fallback to default sections
						for (section in DEFAULT_SECTIONS) {
							addSection(section)
						}
						loadRows()
					}
				})
			}
		})
	}

	override fun loadRows(rows: List<BrowseRowDef>) {
		// Override to make sure it is ignored because we have our custom row management
	}

	private fun loadRows() {
		// Add sections to layout
		mRowsAdapter = ArrayObjectAdapter(PositionableListRowPresenter())
		mCardPresenter = CardPresenter()
		nowPlaying.addToRowsAdapter(mCardPresenter, mRowsAdapter)
		for (row in rows) row.addToRowsAdapter(mCardPresenter, mRowsAdapter)
		footer.addToRowsAdapter(mCardPresenter, mRowsAdapter)
		adapter = mRowsAdapter
	}

	private fun loadRecentlyAdded(): HomeFragmentRow {
		return HomeFragmentLatestRow(views)
	}

	private fun loadLibraryTiles(): HomeFragmentRow {
		val query = ViewQuery()
		return HomeFragmentBrowseRowDefRow(BrowseRowDef(mApplication.getString(R.string.lbl_my_media), query))
	}

	private fun loadLibraryButtons(): HomeFragmentRow {
		// Currently not implemented, fallback to large "library tiles" until this gets implemented
		return loadLibraryTiles()
	}

	private fun loadResume(title: String, mediaTypes: Array<String>): HomeFragmentRow {
		val query = StdItemQuery()
		query.mediaTypes = mediaTypes
		query.recursive = true
		query.imageTypeLimit = 1
		query.enableTotalRecordCount = false
		query.collapseBoxSetItems = false
		query.excludeLocationTypes = arrayOf(LocationType.Virtual)
		query.limit = 50
		query.filters = arrayOf(ItemFilter.IsResumable)
		query.sortBy = arrayOf(ItemSortBy.DatePlayed)
		query.sortOrder = SortOrder.Descending
		return HomeFragmentBrowseRowDefRow(BrowseRowDef(title, query, 0, arrayOf(ChangeTriggerType.VideoQueueChange, ChangeTriggerType.TvPlayback, ChangeTriggerType.MoviePlayback)))
	}

	private fun loadResumeVideo(): HomeFragmentRow {
		return loadResume(mApplication.getString(R.string.lbl_continue_watching), arrayOf(MediaType.Video))
	}

	private fun loadResumeAudio(): HomeFragmentRow {
		return loadResume(mApplication.getString(R.string.lbl_continue_watching), arrayOf(MediaType.Audio))
	}

	private fun loadLatestLiveTvRecordings(): HomeFragmentRow {
		val query = RecordingQuery()
		query.fields = arrayOf(
				ItemFields.Overview,
				ItemFields.PrimaryImageAspectRatio,
				ItemFields.ChildCount
		)
		query.userId = TvApp.getApplication().currentUser.id
		query.enableImages = true
		query.limit = 40
		return HomeFragmentBrowseRowDefRow(BrowseRowDef(mActivity.getString(R.string.lbl_recordings), query))
	}

	private fun loadNextUp(): HomeFragmentRow {
		val query = NextUpQuery()
		query.userId = TvApp.getApplication().currentUser.id
		query.imageTypeLimit = 1
		query.limit = 50
		query.fields = arrayOf(
				ItemFields.PrimaryImageAspectRatio,
				ItemFields.Overview,
				ItemFields.ChildCount
		)
		return HomeFragmentBrowseRowDefRow(BrowseRowDef(mApplication.getString(R.string.lbl_next_up), query, arrayOf(ChangeTriggerType.TvPlayback)))
	}

	private fun loadOnNow(): HomeFragmentRow {
		val query = RecommendedProgramQuery()
		query.isAiring = true
		query.fields = arrayOf(
				ItemFields.Overview,
				ItemFields.PrimaryImageAspectRatio,
				ItemFields.ChannelInfo,
				ItemFields.ChildCount
		)
		query.userId = TvApp.getApplication().currentUser.id
		query.imageTypeLimit = 1
		query.enableTotalRecordCount = false
		query.limit = 20
		return HomeFragmentBrowseRowDefRow(BrowseRowDef(mApplication.getString(R.string.lbl_on_now), query))
	}

	private fun hasResumeRow(): Boolean {
		if (mRowsAdapter == null) return true
		for (i in 0 until mRowsAdapter.size()) {
			val row = mRowsAdapter[i] as ListRow
			if (row.adapter is ItemRowAdapter && (row.adapter as ItemRowAdapter).queryType == QueryType.ContinueWatching) return true
		}
		return false
	}

	companion object {
		// Copied from jellyfin-web (homesections.js#getDefaultSection)
		private val DEFAULT_SECTIONS = arrayOf(
				HomeSectionType.LIBRARY_TILES_SMALL,
				HomeSectionType.RESUME,
				HomeSectionType.RESUME_AUDIO,
				HomeSectionType.LIVE_TV,
				HomeSectionType.NEXT_UP,
				HomeSectionType.LATEST_MEDIA,
				HomeSectionType.NONE
		)
	}
}
