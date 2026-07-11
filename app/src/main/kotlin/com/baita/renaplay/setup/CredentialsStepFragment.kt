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

class CredentialsStepFragment : GuidedStepSupportFragment() {

    private var ip: String = ""
    private var userValue: String = ""
    private var passValue: String = ""
    private val client = SmbClient()

    companion object {
        private const val ARG_IP = "ip"
        private const val ACTION_ID_USER = 1L
        private const val ACTION_ID_PASS = 2L
        private const val ACTION_ID_TEST = 3L

        fun newInstance(ip: String): CredentialsStepFragment {
            val fragment = CredentialsStepFragment()
            fragment.arguments = Bundle().apply { putString(ARG_IP, ip) }
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ip = requireArguments().getString(ARG_IP, "")
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance {
        return Guidance(
            ip,
            getString(R.string.setup_user_pass_desc),
            "",
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_ID_USER)
                .title(getString(R.string.setup_user_hint))
                .descriptionEditable(true)
                .description(userValue)
                .descriptionInputType(InputType.TYPE_CLASS_TEXT)
                .build()
        )
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_ID_PASS)
                .title(getString(R.string.setup_pass_hint))
                .descriptionEditable(true)
                .description(passValue)
                .descriptionInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
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
        when (action.id) {
            ACTION_ID_USER -> userValue = action.description?.toString()?.trim() ?: ""
            ACTION_ID_PASS -> passValue = action.description?.toString() ?: ""
        }
        return GuidedAction.ACTION_ID_NEXT
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id != ACTION_ID_TEST) return

        Toast.makeText(requireContext(), R.string.setup_testing_login, Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                client.listShares(ip, userValue, passValue)
            }
            when (result) {
                is SmbResult.Success -> {
                    Toast.makeText(requireContext(), R.string.setup_login_ok, Toast.LENGTH_SHORT).show()
                    GuidedStepSupportFragment.add(
                        parentFragmentManager,
                        ShareStepFragment.newInstance(ip, userValue, passValue, result.value)
                    )
                }
                is SmbResult.Failure -> {
                    Toast.makeText(requireContext(), R.string.setup_login_fail, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
