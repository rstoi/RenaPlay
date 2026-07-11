package com.baita.renaplay.setup

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.lifecycleScope
import com.baita.renaplay.R
import com.baita.renaplay.smb.SmbClient
import com.baita.renaplay.smb.SmbResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IpStepFragment : GuidedStepSupportFragment() {

    private var ipValue: String = ""
    private val client = SmbClient()

    companion object {
        private const val ACTION_ID_IP = 1L
        private const val ACTION_ID_TEST = 2L
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance {
        return Guidance(
            getString(R.string.setup_title),
            getString(R.string.setup_ip_hint),
            "",
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_ID_IP)
                .title(getString(R.string.setup_ip_hint))
                .descriptionEditable(true)
                .description(ipValue)
                .descriptionInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
                .build()
        )
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_ID_TEST)
                .title(getString(R.string.setup_test_connection))
                .build()
        )
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        if (action.id == ACTION_ID_IP) {
            ipValue = action.description?.toString()?.trim() ?: ""
        }
        return GuidedAction.ACTION_ID_NEXT
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id != ACTION_ID_TEST) return

        if (ipValue.isBlank()) {
            Toast.makeText(requireContext(), R.string.setup_ip_invalid, Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(requireContext(), R.string.setup_testing_ip, Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { client.testHost(ipValue) }
            when (result) {
                is SmbResult.Success -> {
                    Toast.makeText(requireContext(), R.string.setup_ip_ok, Toast.LENGTH_SHORT).show()
                    GuidedStepSupportFragment.add(
                        parentFragmentManager,
                        CredentialsStepFragment.newInstance(ipValue)
                    )
                }
                is SmbResult.Failure -> {
                    Toast.makeText(requireContext(), R.string.setup_ip_fail, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
