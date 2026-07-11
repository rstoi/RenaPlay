package com.baita.renaplay.settings

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.lifecycleScope
import com.baita.renaplay.R
import com.baita.renaplay.data.ServerConfigStore
import com.baita.renaplay.data.SubtitleSettingsStore
import com.baita.renaplay.data.SucaAuthStore
import com.baita.renaplay.pairing.PairingActivity
import com.baita.renaplay.setup.ServerSetupActivity
import com.baita.renaplay.subtitles.HttpClientProvider
import com.baita.renaplay.subtitles.OpenSubtitlesProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ACTION_ID_API_KEY = 1L
private const val ACTION_ID_TEST_KEY = 2L
private const val ACTION_ID_SOURCE_SUBTITLECAT = 3L
private const val ACTION_ID_SOURCE_ADDIC7ED = 4L
private const val ACTION_ID_SOURCE_OPENSUBTITLES = 5L
private const val ACTION_ID_SUCA_PAIRING = 6L
private const val ACTION_ID_CHANGE_SERVER = 7L

class SettingsFragment : GuidedStepSupportFragment() {

    private var apiKeyValue: String = ""

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance {
        return Guidance(getString(R.string.settings_title), "", "", null)
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()
        apiKeyValue = SubtitleSettingsStore.getOpenSubtitlesKey(context)

        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_ID_API_KEY)
                .title(getString(R.string.settings_opensubtitles_key))
                .descriptionEditable(true)
                .description(apiKeyValue)
                .descriptionInputType(InputType.TYPE_CLASS_TEXT)
                .build()
        )
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_ID_TEST_KEY)
                .title(getString(R.string.settings_opensubtitles_test))
                .build()
        )
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_ID_SOURCE_SUBTITLECAT)
                .title(getString(R.string.subtitle_source_subtitlecat))
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(SubtitleSettingsStore.isSourceEnabled(context, SubtitleSettingsStore.SOURCE_SUBTITLECAT))
                .build()
        )
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_ID_SOURCE_ADDIC7ED)
                .title(getString(R.string.subtitle_source_addic7ed))
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(SubtitleSettingsStore.isSourceEnabled(context, SubtitleSettingsStore.SOURCE_ADDIC7ED))
                .build()
        )
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_ID_SOURCE_OPENSUBTITLES)
                .title(getString(R.string.subtitle_source_opensubtitles))
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(SubtitleSettingsStore.isSourceEnabled(context, SubtitleSettingsStore.SOURCE_OPENSUBTITLES, default = false))
                .build()
        )

        val sucaSession = SucaAuthStore.load(context)
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_ID_SUCA_PAIRING)
                .title(getString(R.string.settings_suca_pairing))
                .description(
                    if (sucaSession != null) {
                        getString(R.string.settings_suca_connected, sucaSession.displayName ?: sucaSession.deviceName)
                    } else {
                        getString(R.string.settings_suca_not_connected)
                    }
                )
                .build()
        )
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_ID_CHANGE_SERVER)
                .title(getString(R.string.action_change_server))
                .build()
        )
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        if (action.id == ACTION_ID_API_KEY) {
            apiKeyValue = action.description?.toString()?.trim() ?: ""
            SubtitleSettingsStore.setOpenSubtitlesKey(requireContext(), apiKeyValue)
        }
        return GuidedAction.ACTION_ID_NEXT
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val context = requireContext()
        when (action.id) {
            ACTION_ID_TEST_KEY -> testApiKey()
            ACTION_ID_SOURCE_SUBTITLECAT ->
                SubtitleSettingsStore.setSourceEnabled(context, SubtitleSettingsStore.SOURCE_SUBTITLECAT, action.isChecked)
            ACTION_ID_SOURCE_ADDIC7ED ->
                SubtitleSettingsStore.setSourceEnabled(context, SubtitleSettingsStore.SOURCE_ADDIC7ED, action.isChecked)
            ACTION_ID_SOURCE_OPENSUBTITLES ->
                SubtitleSettingsStore.setSourceEnabled(context, SubtitleSettingsStore.SOURCE_OPENSUBTITLES, action.isChecked)
            ACTION_ID_SUCA_PAIRING -> startActivity(Intent(context, PairingActivity::class.java))
            ACTION_ID_CHANGE_SERVER -> {
                ServerConfigStore.clear(context)
                startActivity(Intent(context, ServerSetupActivity::class.java))
                requireActivity().finish()
            }
        }
    }

    private fun testApiKey() {
        if (apiKeyValue.isBlank()) {
            Toast.makeText(requireContext(), R.string.settings_key_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                OpenSubtitlesProvider(HttpClientProvider.client) { apiKeyValue }.testApiKey(apiKeyValue)
            }
            val message = if (ok) R.string.settings_key_ok else R.string.settings_key_fail
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }
}
