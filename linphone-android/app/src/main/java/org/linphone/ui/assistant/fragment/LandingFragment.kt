/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.assistant.fragment

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.linphone.R
import org.linphone.ui.GenericFragment
import org.linphone.ui.assistant.viewmodel.ThirdPartySipAccountLoginViewModel

/** Uses the site's session flow, preserving email verification and two-factor login. */
class LandingFragment : GenericFragment() {
    companion object {
        private const val ORIGIN = "https://call.agrieldo.com"
        private const val NEXT = "?next=/mobile/ready/"
    }

    private val viewModel: ThirdPartySipAccountLoginViewModel by viewModels()
    private var browser: WebView? = null
    private lateinit var status: TextView
    private lateinit var connect: Button
    private var fetching = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val actions = LinearLayout(context)

        fun action(label: Int, path: String) {
            actions.addView(
                Button(context).apply {
                setText(label)
                setOnClickListener { browser?.loadUrl(ORIGIN + path + NEXT) }
            },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        action(R.string.connectdial_sign_in, "/accounts/login/")
        action(R.string.connectdial_sign_up, "/accounts/signup/")
        root.addView(actions)
        status = TextView(context).apply { setText(R.string.connectdial_website_login_help) }
        root.addView(status)
        connect = Button(context).apply {
            setText(R.string.connectdial_connect_phone)
            setOnClickListener { provision() }
        }
        root.addView(connect)
        browser = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    // Credentials and navigation stay on the configured HTTPS website.
                    return uri.scheme != "https" || uri.host != "call.agrieldo.com" ||
                        (uri.port != -1 && uri.port != 443)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (url.startsWith("$ORIGIN/mobile/ready/")) provision()
                }

                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError
                ) {
                    if (request.isForMainFrame) status.setText(R.string.connectdial_page_error)
                }
            }
            loadUrl("$ORIGIN/accounts/login/$NEXT")
        }
        root.addView(browser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeToastEvents(viewModel)
        viewModel.registrationInProgress.observe(viewLifecycleOwner) { busy ->
            connect.isEnabled = !fetching && busy != true
        }
        viewModel.accountLoggedInEvent.observe(viewLifecycleOwner) { event ->
            event.consume {
                // Do not retain a website session after the phone has been configured.
                CookieManager.getInstance().removeAllCookies {
                    CookieManager.getInstance().flush()
                }
                viewModel.password.value = ""
                requireActivity().finish()
            }
        }
        viewModel.accountLoginErrorEvent.observe(viewLifecycleOwner) { event ->
            event.consume { status.text = it }
        }
    }

    private fun provision() {
        if (fetching || viewModel.registrationInProgress.value == true) return
        fetching = true
        connect.isEnabled = false
        status.setText(R.string.connectdial_connecting_phone)
        val cookies = CookieManager.getInstance().getCookie(ORIGIN).orEmpty()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (code, data) = withContext(Dispatchers.IO) {
                    val connection = URL("$ORIGIN/mobile/provision/").openConnection() as HttpsURLConnection
                    try {
                        connection.instanceFollowRedirects = false
                        connection.connectTimeout = 15000
                        connection.readTimeout = 15000
                        connection.setRequestProperty("Cookie", cookies)
                        connection.setRequestProperty("Accept", "application/json")
                        val code = connection.responseCode
                        val stream = if (code == 200) connection.inputStream else connection.errorStream
                        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        code to JSONObject(body)
                    } finally {
                        connection.disconnect()
                    }
                }
                if (code == 200) {
                    viewModel.loginWebsiteAccount(data)
                } else {
                    status.text = data.optString("message", getString(R.string.connectdial_connection_error))
                }
            } catch (_: IOException) {
                status.setText(R.string.connectdial_connection_error)
            } catch (_: org.json.JSONException) {
                status.setText(R.string.connectdial_connection_error)
            } finally {
                fetching = false
                connect.isEnabled = viewModel.registrationInProgress.value != true
            }
        }
    }

    override fun onDestroyView() {
        browser?.stopLoading()
        browser?.destroy()
        browser = null
        super.onDestroyView()
    }
}
