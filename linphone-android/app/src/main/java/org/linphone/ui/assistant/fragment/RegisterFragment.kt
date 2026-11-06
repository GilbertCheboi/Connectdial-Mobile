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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.linphone.R
import org.linphone.databinding.AssistantRegisterFragmentBinding
import org.linphone.ui.GenericActivity
import org.linphone.ui.GenericFragment
import org.linphone.ui.assistant.viewmodel.AccountCreationViewModel

class RegisterFragment : GenericFragment() {
    private lateinit var binding: AssistantRegisterFragmentBinding
    private val viewModel: AccountCreationViewModel by navGraphViewModels(R.id.assistant_nav_graph)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = AssistantRegisterFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = viewModel
        binding.setBackClickListener { findNavController().popBackStack() }
        binding.setLoginClickListener { findNavController().popBackStack() }
        binding.setCreateAccountClickListener { register() }
        binding.setOpenSubscribeWebPageClickListener {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://call.agrieldo.com/accounts/signup/")
                )
            )
        }
    }

    private fun register() {
        if (viewModel.operationInProgress.value == true) return
        val password = binding.password.text.toString()
        val body = JSONObject()
            .put("username", binding.username.text.toString().trim())
            .put("email", binding.phoneNumber.text.toString().trim())
            .put("password", password)
            .put("password_confirmation", password)
        viewModel.operationInProgress.value = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { ConnectDialApi.request("auth/register/", body) }
                viewModel.password.value = ""
                AlertDialog.Builder(requireContext())
                    .setTitle("Account created")
                    .setMessage(result.optString("message", "Your ConnectDial account is ready. Ask your administrator to assign a phone extension, then sign in."))
                    .setPositiveButton(android.R.string.ok) { _, _ -> findNavController().popBackStack() }
                    .show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                (requireActivity() as GenericActivity).showRedToast(
                    error.message ?: "Unable to create account. Please try again.", R.drawable.warning_circle
                )
            } finally {
                viewModel.operationInProgress.value = false
            }
        }
    }
}
