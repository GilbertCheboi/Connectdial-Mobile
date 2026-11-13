package org.linphone.ui.assistant.fragment

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.linphone.R

/** Tokens and context live only for this assistant session; no mock context can start SIP. */
internal class ConnectDialOnboarding(
    private val owner: Fragment,
    private val token: String,
    private val connectLegacyPhone: () -> Unit
) {
    private var activeContextName: String? = null

    private fun work(operation: suspend () -> Unit) {
        owner.viewLifecycleOwner.lifecycleScope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message(owner.getString(R.string.connectdial_request_failed), error.message.orEmpty())
            }
        }
    }

    private suspend fun request(path: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) { ConnectDialApi.request(path, body, token) }

    private fun message(title: String, text: String, then: (() -> Unit)? = null) {
        if (!owner.isAdded || owner.view == null) return
        AlertDialog.Builder(owner.requireContext()).setTitle(title).setMessage(text)
            .setPositiveButton(android.R.string.ok) { _, _ -> then?.invoke() }.show()
    }

    private fun menu(title: String, labels: List<String>, returnHome: Boolean = true, selected: (Int) -> Unit) {
        if (!owner.isAdded || owner.view == null) return
        AlertDialog.Builder(owner.requireContext()).setTitle(title)
            .setItems(labels.toTypedArray()) { _, index -> selected(index) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> if (returnHome) show() }.show()
    }

    private fun inputs(title: String, labels: List<String>, submit: (List<String>) -> Unit) {
        if (!owner.isAdded || owner.view == null) return
        val layout = LinearLayout(owner.requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val margin = (20 * resources.displayMetrics.density).toInt()
            setPadding(margin, margin / 2, margin, 0)
        }
        val fields = labels.map { label ->
            EditText(owner.requireContext()).apply {
                hint = label
                contentDescription = label
                inputType = InputType.TYPE_CLASS_TEXT
                maxLines = 2
                layout.addView(this)
            }
        }
        val dialog = AlertDialog.Builder(owner.requireContext()).setTitle(title).setView(layout)
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel) { _, _ -> show() }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val values = fields.map { it.text.toString().trim() }
                if (values.any { it.isEmpty() }) {
                    fields.forEach { if (it.text.isNullOrBlank()) it.error = owner.getString(R.string.connectdial_required) }
                } else {
                    dialog.dismiss()
                    submit(values)
                }
            }
        }
        dialog.show()
    }

    fun show(): Unit = work {
        val data = request("onboarding/contexts/")
        val contexts = data.getJSONArray("contexts")
        val labels = mutableListOf<String>()
        for (index in 0 until contexts.length()) {
            val context = contexts.getJSONObject(index)
            labels.add("${context.getString("name")} · ${context.getString("status")}")
        }
        val count = labels.size
        labels.add(owner.getString(R.string.connectdial_personal_create))
        labels.add(owner.getString(R.string.connectdial_join))
        labels.add(owner.getString(R.string.connectdial_apply))
        labels.add(owner.getString(R.string.connectdial_accept_invitation))
        labels.add(owner.getString(R.string.connectdial_requests))
        labels.add(owner.getString(R.string.connectdial_legacy_phone))
        if (data.optBoolean("platform_admin")) labels.add(owner.getString(R.string.connectdial_platform_review))
        val title = activeContextName?.let { "${owner.getString(R.string.connectdial_accounts)} · $it" }
            ?: owner.getString(R.string.connectdial_accounts)
        menu(title, labels, returnHome = false) { selected ->
            if (selected < count) {
                context(contexts.getJSONObject(selected))
            } else {
                when (selected - count) {
                    0 -> work {
                        request("accounts/individual/register/", JSONObject())
                        show()
                    }
                    1 -> inputs(owner.getString(R.string.connectdial_join), listOf(owner.getString(R.string.connectdial_search))) {
                        discover(it[0])
                    }
                    2 -> inputs(
                        owner.getString(R.string.connectdial_apply),
                        listOf(
                        owner.getString(R.string.connectdial_org_name), owner.getString(R.string.connectdial_contact_email)
                    )
                    ) { values ->
                        work {
                        request("organizations/registration-requests/", JSONObject().put("name", values[0]).put("contact_email", values[1]))
                        message(owner.getString(R.string.connectdial_submitted), owner.getString(R.string.connectdial_application_pending)) {
                            show()
                        }
                    }
                    }
                    3 -> inputs(owner.getString(R.string.connectdial_accept_invitation), listOf(owner.getString(R.string.connectdial_invitation_token))) { values ->
                        work {
                        request("organizations/invitations/accept/", JSONObject().put("token", values[0]))
                        show()
                    }
                    }
                    4 -> {
                        val rows = mutableListOf<String>()
                        for (key in listOf("applications", "join_requests")) {
                            val requests = data.optJSONArray(key) ?: continue
                            for (index in 0 until requests.length()) {
                                val row = requests.getJSONObject(index)
                                rows.add("${row.optString("name", "Organization #${row.optString("organization_id")}")} · ${row.getString("status")}")
                            }
                        }
                        message(
                            owner.getString(R.string.connectdial_requests),
                            rows.joinToString("\n").ifEmpty {
                            owner.getString(R.string.connectdial_no_requests)
                        }
                        ) { show() }
                    }
                    5 -> connectLegacyPhone()
                    6 -> reviews()
                }
            }
        }
    }

    private fun context(context: JSONObject): Unit = work {
        val key = context.getString("context")
        val encoded = java.net.URLEncoder.encode(key, "UTF-8")
        val result = request("pbx/extensions/me/?context=$encoded").getJSONArray("results")
        activeContextName = context.getString("name")
        val lines = mutableListOf(owner.getString(R.string.connectdial_mock_pending))
        for (index in 0 until result.length()) {
            val row = result.getJSONObject(index)
            lines.add("${row.getString("number")} · ${row.getString("status")}")
        }
        val admin = context.optString("role") in listOf("OWNER", "ADMIN")
        if (!admin) {
            message(context.getString("name"), lines.joinToString("\n")) { show() }
        } else {
            menu(
                context.getString("name"),
                listOf(
                    owner.getString(R.string.connectdial_phone_status),
                owner.getString(R.string.connectdial_membership_review), owner.getString(R.string.connectdial_invite)
                )
            ) { index ->
                val organizationId = context.getInt("organization_id")
                when (index) {
                    0 -> message(context.getString("name"), lines.joinToString("\n")) { show() }
                    1 -> reviews(organizationId)
                    2 -> inputs(owner.getString(R.string.connectdial_invite), listOf(owner.getString(R.string.connectdial_recipient_email))) { values ->
                        work {
                        val invitation =
                            request("organizations/$organizationId/invitations/", JSONObject().put("email", values[0]))
                        val inviteToken = invitation.getString("token")
                        AlertDialog.Builder(owner.requireContext()).setTitle(R.string.connectdial_invitation_created)
                            .setMessage("$inviteToken\n\n${invitation.getString("message")}")
                            .setNeutralButton(R.string.connectdial_copy_token) { _, _ ->
                                val clipboard = owner.requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(owner.getString(R.string.connectdial_invitation_token), inviteToken))
                                show()
                            }.setPositiveButton(android.R.string.ok) { _, _ -> show() }.show()
                    }
                    }
                }
            }
        }
    }

    private fun discover(search: String, page: Int = 1): Unit = work {
        val encoded = java.net.URLEncoder.encode(search, "UTF-8")
        val response = request("organizations/discoverable/?search=$encoded&page=$page")
        val rows = response.getJSONArray("results")
        val labels = (0 until rows.length()).map { rows.getJSONObject(it).getString("name") }.toMutableList()
        val hasNext = !response.isNull("next")
        if (hasNext) labels.add(owner.getString(R.string.connectdial_more))
        if (labels.isEmpty()) {
            message(owner.getString(R.string.connectdial_join), owner.getString(R.string.connectdial_no_organizations)) { show() }
        } else {
            menu(owner.getString(R.string.connectdial_join), labels) { selected ->
            if (selected == rows.length()) {
                discover(search, page + 1)
            } else {
                work {
                val id = rows.getJSONObject(selected).getInt("id")
                request("organizations/$id/join-requests/", JSONObject())
                message(owner.getString(R.string.connectdial_submitted), owner.getString(R.string.connectdial_membership_pending)) {
                    show()
                }
            }
            }
        }
        }
    }

    private fun reviews(organizationId: Int? = null, page: Int = 1): Unit = work {
        val path = if (organizationId == null) "admin/organizations/registration-requests/" else "organizations/$organizationId/join-requests/"
        val response = request("$path?page=$page")
        val rows = response.getJSONArray("results")
        val pending = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.getString("status") == "PENDING" }
        val labels = pending.map { it.optString("name", it.optString("username")) }.toMutableList()
        val hasNext = !response.isNull("next")
        if (hasNext) labels.add(owner.getString(R.string.connectdial_more))
        if (labels.isEmpty()) {
            message(owner.getString(R.string.connectdial_requests), owner.getString(R.string.connectdial_no_requests)) { show() }
        } else {
            menu(owner.getString(R.string.connectdial_requests), labels) { selected ->
            if (selected == pending.size) {
                reviews(organizationId, page + 1)
            } else {
                menu(labels[selected], listOf(owner.getString(R.string.connectdial_approve), owner.getString(R.string.connectdial_reject))) { decision ->
                    work {
                val action = if (decision == 0) "approve" else "reject"
                request("$path${pending[selected].getInt("id")}/$action/", JSONObject())
                reviews(organizationId)
            }
                }
            }
        }
        }
    }
}
