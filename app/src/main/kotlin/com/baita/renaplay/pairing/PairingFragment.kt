package com.baita.renaplay.pairing

import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.lifecycleScope
import com.baita.renaplay.R
import com.baita.renaplay.data.SucaAuthStore
import com.baita.renaplay.suca.SucaApiClient
import com.baita.renaplay.suca.SucaResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ACTION_ID_CODE = 1L
private const val ACTION_ID_PAIR = 2L
private const val ACTION_ID_UNPAIR = 3L

class PairingFragment : GuidedStepSupportFragment() {

    private var codeValue: String = ""

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance {
        val session = SucaAuthStore.load(requireContext())
        val description = if (session != null) {
            getString(R.string.pairing_status_connected, session.displayName ?: session.deviceName)
        } else {
            getString(R.string.pairing_instructions)
        }
        val icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_suca_logo)
        return Guidance(getString(R.string.pairing_title), description, "", icon)
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()
        val session = SucaAuthStore.load(context)

        if (session == null) {
            actions.add(
                GuidedAction.Builder(context)
                    .id(ACTION_ID_CODE)
                    .title(getString(R.string.pairing_code_hint))
                    .descriptionEditable(true)
                    .description("")
                    .descriptionInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
                    .build()
            )
            actions.add(
                GuidedAction.Builder(context)
                    .id(ACTION_ID_PAIR)
                    .title(getString(R.string.pairing_action_pair))
                    .build()
            )
        } else {
            actions.add(
                GuidedAction.Builder(context)
                    .id(ACTION_ID_UNPAIR)
                    .title(getString(R.string.pairing_action_unpair))
                    .build()
            )
        }
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        if (action.id == ACTION_ID_CODE) {
            codeValue = action.description?.toString()?.trim()?.uppercase() ?: ""
        }
        return GuidedAction.ACTION_ID_NEXT
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_ID_PAIR -> pair()
            ACTION_ID_UNPAIR -> unpair()
        }
    }

    private fun pair() {
        if (codeValue.isBlank()) {
            Toast.makeText(requireContext(), R.string.pairing_code_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                SucaApiClient(context).claimPairing(
                    baseUrl = SucaAuthStore.DEFAULT_BASE_URL,
                    code = codeValue,
                    deviceName = "Fire TV",
                    deviceType = "firetv",
                    model = Build.MODEL ?: "unknown",
                    os = "FireOS",
                    version = Build.VERSION.RELEASE ?: "unknown"
                )
            }
            when (result) {
                is SucaResult.Success -> {
                    SucaAuthStore.save(context, result.value)
                    Toast.makeText(requireContext(), R.string.pairing_success, Toast.LENGTH_LONG).show()
                    requireActivity().finish()
                }
                is SucaResult.Failure -> {
                    val message = result.hint ?: result.message
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun unpair() {
        SucaAuthStore.clear(requireContext())
        Toast.makeText(requireContext(), R.string.pairing_unpaired, Toast.LENGTH_SHORT).show()
        requireActivity().finish()
    }
}
