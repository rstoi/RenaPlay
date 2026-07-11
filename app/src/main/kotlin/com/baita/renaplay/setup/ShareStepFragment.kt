package com.baita.renaplay.setup

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.lifecycleScope
import com.baita.renaplay.R
import com.baita.renaplay.browse.BrowseActivity
import com.baita.renaplay.data.ServerConfig
import com.baita.renaplay.data.ServerConfigStore
import com.baita.renaplay.smb.SmbClient
import com.baita.renaplay.smb.SmbResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareStepFragment : GuidedStepSupportFragment() {

    private var ip: String = ""
    private var userValue: String = ""
    private var passValue: String = ""
    private var shares: List<String> = emptyList()
    private var manualShareValue: String = ""
    private val client = SmbClient()

    companion object {
        private const val ARG_IP = "ip"
        private const val ARG_USER = "user"
        private const val ARG_PASS = "pass"
        private const val ARG_SHARES = "shares"
        private const val ACTION_ID_MANUAL_SHARE = 1000L
        private const val ACTION_ID_MANUAL_TEST = 1001L

        fun newInstance(ip: String, user: String, pass: String, shares: List<String>): ShareStepFragment {
            val fragment = ShareStepFragment()
            fragment.arguments = Bundle().apply {
                putString(ARG_IP, ip)
                putString(ARG_USER, user)
                putString(ARG_PASS, pass)
                putStringArrayList(ARG_SHARES, ArrayList(shares))
            }
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        ip = args.getString(ARG_IP, "")
        userValue = args.getString(ARG_USER, "")
        passValue = args.getString(ARG_PASS, "")
        shares = args.getStringArrayList(ARG_SHARES) ?: emptyList()
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance {
        return Guidance(
            getString(R.string.setup_choose_share),
            "",
            ip,
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        if (shares.isEmpty()) {
            // Alguns servidores (Samba embarcado, NAS antigos) não expõem a lista de
            // compartilhamentos via enumeração automática. Deixa o usuário digitar o nome.
            actions.add(
                GuidedAction.Builder(requireContext())
                    .id(GuidedAction.NO_ID.toLong())
                    .title(getString(R.string.setup_no_shares))
                    .infoOnly(true)
                    .build()
            )
            actions.add(
                GuidedAction.Builder(requireContext())
                    .id(ACTION_ID_MANUAL_SHARE)
                    .title(getString(R.string.setup_manual_share_hint))
                    .descriptionEditable(true)
                    .description(manualShareValue)
                    .descriptionInputType(InputType.TYPE_CLASS_TEXT)
                    .build()
            )
            actions.add(
                GuidedAction.Builder(requireContext())
                    .id(ACTION_ID_MANUAL_TEST)
                    .title(getString(R.string.setup_test_connection))
                    .build()
            )
            return
        }
        shares.forEachIndexed { index, name ->
            actions.add(
                GuidedAction.Builder(requireContext())
                    .id(index.toLong() + 1)
                    .title(name)
                    .build()
            )
        }
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        if (action.id == ACTION_ID_MANUAL_SHARE) {
            manualShareValue = action.description?.toString()?.trim() ?: ""
        }
        return GuidedAction.ACTION_ID_NEXT
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ACTION_ID_MANUAL_TEST) {
            testManualShare()
            return
        }
        if (action.id == ACTION_ID_MANUAL_SHARE || action.id == GuidedAction.NO_ID.toLong()) return

        val shareName = action.title?.toString() ?: return
        saveAndContinue(shareName)
    }

    private fun testManualShare() {
        if (manualShareValue.isBlank()) {
            Toast.makeText(requireContext(), R.string.setup_ip_invalid, Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(requireContext(), R.string.setup_testing_connection, Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                client.listFiles(ip, manualShareValue, "", userValue, passValue)
            }
            when (result) {
                is SmbResult.Success -> saveAndContinue(manualShareValue)
                is SmbResult.Failure -> Toast.makeText(requireContext(), R.string.setup_share_not_found, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveAndContinue(shareName: String) {
        val config = ServerConfig(ip = ip, user = userValue, password = passValue, share = shareName)
        ServerConfigStore.save(requireContext(), config)
        Toast.makeText(requireContext(), R.string.setup_continue, Toast.LENGTH_SHORT).show()
        startActivity(Intent(requireContext(), BrowseActivity::class.java))
        requireActivity().finish()
    }
}
