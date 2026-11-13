package org.linphone.ui.billing

import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import org.linphone.ui.main.viewmodel.AbstractMainViewModel
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Account
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.ui.assistant.fragment.ConnectDialApi
import org.linphone.ui.main.MainActivity

/** Identity-bound airtime actions presented by the existing main header. */
internal class BillingController(private val activity: MainActivity) {
    private val store = try { BillingSessionStore(activity) } catch (_: Exception) { null }
    private val available = MutableLiveData(false)
    private val description = MutableLiveData("Airtime balance and buy airtime")

    fun bind(viewModel: AbstractMainViewModel, owner: LifecycleOwner) {
        available.observe(owner) { viewModel.airtimeAvailable.value = it }
        description.observe(owner) { viewModel.airtimeDescription.value = it }
        viewModel.openAirtimeEvent.observe(owner) { event -> event.consume { open() } }
    }

    private var refreshJob: Job? = null
    private var purchaseJob: Job? = null
    private var dialog: AlertDialog? = null
    private var current: BillingSessionStore.Session? = null
    private var wallet: JSONObject? = null
    private val listener = object : CoreListenerStub() {
        override fun onDefaultAccountChanged(core: Core, account: Account?) { validate(account) }

        override fun onAccountRemoved(core: Core, account: Account) {
            val identity = account.params.identityAddress
            if (store?.load()?.identity == BillingSessionStore.identity(identity?.username, identity?.domain)) invalidate()
        }
    }

    init {
        coreContext.postOnCoreThread { core -> core.addListener(listener) }
    }

    private fun validate(account: Account?) {
        val identity = account?.params?.identityAddress
        val session = store?.load() ?: return
        if (session.identity != BillingSessionStore.identity(identity?.username, identity?.domain)) invalidate()
    }

    private fun invalidate() {
        val revoked = store?.load()
        store?.clear()
        activity.runOnUiThread {
            current = null; wallet = null; available.value = false
            dialog?.dismiss(); dialog = null; purchaseJob?.cancel()
        }
        if (revoked != null) {
            activity.lifecycleScope.launch(Dispatchers.IO) {
            try { ConnectDialApi.request("billing/logout/", JSONObject(), revoked.token, billing = true) } catch (_: Exception) { }
        }
        }
    }

    private suspend fun selectedIdentity(): String = suspendCancellableCoroutine { continuation ->
        coreContext.postOnCoreThread { core ->
            val address = core.defaultAccount?.params?.identityAddress
            if (continuation.isActive) continuation.resume(BillingSessionStore.identity(address?.username, address?.domain))
        }
    }

    private suspend fun session(): BillingSessionStore.Session? {
        val saved = store?.load() ?: return null
        if (saved.identity != selectedIdentity()) { invalidate(); return null }
        return saved
    }

    private suspend fun request(path: String, body: JSONObject? = null): JSONObject {
        val saved = session() ?: throw java.io.IOException("Sign in again to access airtime.")
        try {
            val response =
                withContext(Dispatchers.IO) { ConnectDialApi.request("billing/$path", body, saved.token, billing = true) }
            if (store?.load() != saved || session() != saved) throw java.io.IOException("Account changed. Sign in again to access airtime.")
            current = saved
            return response
        } catch (error: ConnectDialApi.HttpException) {
            if (error.status == 401 || error.status == 403) invalidate()
            throw error
        }
    }

    private suspend fun refresh() {
        if (session() == null) { available.value = false; return }
        try {
            val data = request("wallet/")
            wallet = data
            val held = if (!data.isNull("active_call")) " · KES ${data.getString("reserved")} held" else ""
            description.value = "KES ${data.getString("available")} · ${data.getString("available_minutes")} min$held · Buy airtime"
            available.value = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (store?.load() != null) { description.value = "Airtime unavailable · Retry"; available.value = true }
        }
    }

    fun resume() {
        refreshJob?.cancel()
        refreshJob = activity.lifecycleScope.launch { while (true) { refresh(); delay(30000) } }
    }

    fun pause() { refreshJob?.cancel(); purchaseJob?.cancel(); dialog?.dismiss(); dialog = null }

    fun destroy() { pause(); coreContext.postOnCoreThread { core -> core.removeListener(listener) } }

    private fun message(text: String) {
        if (activity.isFinishing || current != store?.load()) return
        dialog?.dismiss()
        dialog = AlertDialog.Builder(activity).setTitle("Airtime").setMessage(text).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun open() {
        if (purchaseJob?.isActive == true) return
        purchaseJob = activity.lifecycleScope.launch {
            refresh()
            val data = wallet ?: return@launch
            if (session() == null) return@launch
            val details = "Available: KES ${data.getString("available")} · ${data.getString("available_minutes")} minutes\n" +
                "Held for an active call: KES ${data.getString("reserved")}\nDebt: KES ${data.getString("debt")}\n" +
                "KSh 10 per answered minute, billed per second. Unused credit does not expire."
            if (!data.optBoolean("payment_enabled")) {
                message(details + "\n\n" + data.optString("payment_message", "M-PESA checkout is awaiting merchant API configuration. No payment can be taken yet."))
                return@launch
            }
            val packages = data.getJSONArray("packages")
            dialog = AlertDialog.Builder(activity).setTitle("Buy airtime")
                .setItems(
                    Array(packages.length()) { i ->
                        packages.getJSONObject(i).let {
                    "KES ${it.getString("amount")} · ${it.getInt("minutes")} minutes"
                }
                }
                ) { _, index ->
                    if (data.optString("payment_provider") == "paystack") {
                        checkout(packages.getJSONObject(index))
                    } else {
                        phone(packages.getJSONObject(index))
                    }
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private fun checkout(pack: JSONObject) {
        val key = UUID.randomUUID().toString()
        dialog = AlertDialog.Builder(activity).setTitle("KES ${pack.getString("amount")} airtime")
            .setMessage("Pay with M-PESA or card through Paystack. Credit appears after verification.")
            .setPositiveButton("Continue to Paystack") { _, _ ->
                purchaseJob = activity.lifecycleScope.launch {
                    try {
                        val order = request(
                            "purchases/",
                            JSONObject().put("package_id", pack.getString("id"))
                            .put("idempotency_key", key)
                        )
                        val address = order.optString("checkout_url")
                        if (!address.matches(Regex("https://checkout\\.paystack\\.com/[A-Za-z0-9_-]+"))) {
                            message("Payment ${order.optString("status")}. Refresh your balance before retrying.")
                            return@launch
                        }
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        message(error.message ?: "Unable to open checkout. Refresh your balance before retrying.")
                    }
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun phone(pack: JSONObject) {
        val input = EditText(activity).apply {
            hint = "M-PESA phone number (254712345678)"; contentDescription = hint
            inputType = InputType.TYPE_CLASS_PHONE; setSingleLine(true)
        }
        val key = UUID.randomUUID().toString()
        val prompt = AlertDialog.Builder(activity).setTitle("KES ${pack.getString("amount")} airtime")
            .setMessage("Kopo Kopo · Till 4336541. Approve the prompt on your phone. Never enter your PIN here.")
            .setView(input).setPositiveButton("Send M-PESA prompt", null).setNegativeButton(android.R.string.cancel, null).create()
        dialog = prompt
        prompt.setOnShowListener {
            prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val number = input.text.toString().trim()
                if (!number.matches(Regex("\\+?[0-9]{9,15}"))) {
                    input.error = "Enter a valid M-PESA phone number"; return@setOnClickListener
                }
                prompt.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                purchaseJob = activity.lifecycleScope.launch {
                    try {
                        val order =
                            request("purchases/", JSONObject().put("package_id", pack.getString("id")).put("phone_number", number).put("idempotency_key", key))
                        prompt.dismiss()
                        message("Payment pending. Approve the M-PESA prompt on your phone. Credit appears after verification.")
                        repeat(24) {
                            delay(5000)
                            val result = request("purchases/${order.getString("id")}/")
                            val status = result.getString("status").lowercase()
                            if (status !in listOf("pending", "created", "initiated", "processing", "submitted")) {
                                refresh(); message("Payment $status."); return@launch
                            }
                        }
                        message("Payment is still pending. Refresh your balance later. Do not pay again while confirmation is pending.")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        prompt.dismiss(); message(error.message ?: "Unable to request payment. Refresh your balance before retrying.")
                    }
                }
            }
        }
        prompt.show()
    }
}
