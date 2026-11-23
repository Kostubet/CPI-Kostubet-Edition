package com.cosmod.launcherglass.ui

import android.util.Patterns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.community.CommunityAccount
import com.cosmod.launcherglass.community.CommunityApi
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class CatalogMode { BROWSE, ACCOUNT, VERIFY, PUBLISH }

@Composable
internal fun CommunityCatalog(
    snapshot: () -> String,
    onPreview: (SettingsTransfer.Document) -> Unit,
) {
    if (!CommunityAccount.configured) {
        Card(Modifier.fillMaxWidth()) {
            BasicComponent(title = stringResource(R.string.community_unavailable),
                summary = stringResource(R.string.community_unavailable_hint))
        }
        return
    }

    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(CatalogMode.BROWSE) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(0) }
    var email by remember { mutableStateOf<String?>(null) }
    var nickname by remember { mutableStateOf<String?>(null) }
    var emailInput by remember { mutableStateOf(TextFieldValue()) }
    var codeInput by remember { mutableStateOf(TextFieldValue()) }
    var nicknameInput by remember { mutableStateOf(TextFieldValue()) }
    var titleInput by remember { mutableStateOf(TextFieldValue()) }
    var descriptionInput by remember { mutableStateOf(TextFieldValue()) }
    var query by remember { mutableStateOf(TextFieldValue()) }
    var appliedQuery by remember { mutableStateOf("") }
    var mine by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<CommunityApi.Entry>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var refreshOnOpen by remember { mutableStateOf(true) }
    var removeEntry by remember { mutableStateOf<CommunityApi.Entry?>(null) }
    var showRemoval by remember { mutableStateOf(false) }

    fun <T> request(
        work: CommunityAccount.() -> T,
        whenDone: (T) -> Unit,
    ) {
        if (busy) return
        busy = true
        status = 0
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { CommunityAccount.get(context).work() }
                whenDone(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: CommunityApi.Failure) {
                status = when {
                    error.code in setOf("otp_expired", "invalid_otp") -> R.string.community_invalid_code
                    error.code == "23505" -> R.string.community_name_taken
                    error.status == 429 -> R.string.community_rate_limited
                    error.status == 401 || error.status == 403 -> R.string.community_sign_in_required
                    error.status == 404 -> R.string.community_missing_entry
                    else -> R.string.community_error
                }
                if (error.code == "sign_in") {
                    email = null
                    nickname = null
                    mine = false
                    mode = CatalogMode.ACCOUNT
                }
            } catch (_: Exception) {
                status = R.string.community_error
            } finally {
                busy = false
            }
        }
    }

    fun refresh(
        filter: String = appliedQuery,
        own: Boolean = mine,
        append: Boolean = false,
        success: Int = 0,
    ) {
        val after = if (append) entries.lastOrNull() else null
        request({ search(filter, after, own) }) { page ->
            appliedQuery = filter
            mine = own
            entries = if (append) entries + page else page
            hasMore = page.size == CommunityApi.PAGE_SIZE
            loaded = true
            status = success
        }
    }

    fun openAccount(publish: Boolean) {
        focus.clearFocus()
        request({
            val signedInEmail = this.email
            signedInEmail to if (signedInEmail != null) name() else null
        }) { (signedInEmail, publicName) ->
            email = signedInEmail
            nickname = publicName
            nicknameInput = TextFieldValue(publicName.orEmpty())
            mode = if (publish && publicName != null) CatalogMode.PUBLISH else CatalogMode.ACCOUNT
            if (publish && publicName == null) status = R.string.community_sign_in_required
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        if (busy) Text(stringResource(R.string.community_working), modifier = Modifier.padding(horizontal = 16.dp))
        if (status != 0) Text(stringResource(status), modifier = Modifier.padding(horizontal = 16.dp))
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (mode) {
                    CatalogMode.BROWSE -> {
                        CatalogField(query, { query = it }, R.string.community_search, 80)
                        CouiAction(stringResource(R.string.community_find), true, {
                            focus.clearFocus()
                            refresh(query.text.trim())
                        }, Modifier.fillMaxWidth(), enabled = !busy)
                        CouiAction(stringResource(R.string.community_publish), false, {
                            openAccount(publish = true)
                        }, Modifier.fillMaxWidth(), enabled = !busy)
                        CouiAction(stringResource(R.string.community_account), false, {
                            openAccount(publish = false)
                        }, Modifier.fillMaxWidth(), enabled = !busy)
                    }

                    CatalogMode.ACCOUNT -> {
                        val currentEmail = email
                        if (currentEmail == null) {
                            Text(stringResource(R.string.community_email_hint))
                            CatalogField(emailInput, { emailInput = it }, R.string.community_email, 254, KeyboardType.Email)
                            val address = emailInput.text.trim()
                            CouiAction(stringResource(R.string.community_send_code), true, {
                                focus.clearFocus()
                                request({ sendCode(address) }) {
                                    codeInput = TextFieldValue()
                                    mode = CatalogMode.VERIFY
                                }
                            }, Modifier.fillMaxWidth(),
                                enabled = !busy && Patterns.EMAIL_ADDRESS.matcher(address).matches())
                        } else {
                            Text(currentEmail)
                            CatalogField(nicknameInput, { nicknameInput = it }, R.string.community_name, 24)
                            Text(stringResource(R.string.community_name_hint))
                            val name = nicknameInput.text.trim()
                            CouiAction(stringResource(R.string.community_save_name), true, {
                                focus.clearFocus()
                                request({ setName(name) }) {
                                    nickname = name
                                    status = R.string.community_saved
                                }
                            }, Modifier.fillMaxWidth(), enabled = !busy && CommunityApi.NICKNAME.matches(name))
                            CouiAction(stringResource(R.string.community_sign_out), false, {
                                request({ signOut() }) {
                                    email = null
                                    nickname = null
                                    emailInput = TextFieldValue()
                                    codeInput = TextFieldValue()
                                    mine = false
                                    appliedQuery = ""
                                    query = TextFieldValue()
                                    entries = emptyList()
                                    hasMore = false
                                    loaded = false
                                    mode = CatalogMode.BROWSE
                                    refreshOnOpen = true
                                }
                            }, Modifier.fillMaxWidth(), enabled = !busy)
                        }
                    }

                    CatalogMode.VERIFY -> {
                        val address = emailInput.text.trim()
                        Text(stringResource(R.string.community_code_hint, address))
                        CatalogField(codeInput, { codeInput = it }, R.string.community_code, 8, KeyboardType.Number)
                        CouiAction(stringResource(R.string.community_verify), true, {
                            focus.clearFocus()
                            request({ signIn(address, codeInput.text.trim()) }) {
                                email = it
                                codeInput = TextFieldValue()
                                mode = CatalogMode.ACCOUNT
                            }
                        }, Modifier.fillMaxWidth(),
                            enabled = !busy && codeInput.text.length in 6..8 && codeInput.text.all { it in '0'..'9' })
                        CouiAction(stringResource(R.string.community_resend), false, {
                            request({ sendCode(address) }) { status = R.string.community_sent }
                        }, Modifier.fillMaxWidth(), enabled = !busy)
                    }

                    CatalogMode.PUBLISH -> {
                        Text(stringResource(R.string.community_publish_hint, nickname.orEmpty()))
                        CatalogField(titleInput, { titleInput = it }, R.string.community_config_name, SettingsTransfer.TITLE_LIMIT)
                        CatalogField(descriptionInput, { descriptionInput = it }, R.string.community_description,
                            SettingsTransfer.DESCRIPTION_LIMIT, multiline = true)
                        CouiAction(stringResource(R.string.community_publish_action), true, {
                            focus.clearFocus()
                            val title = titleInput.text.trim()
                            val description = descriptionInput.text.trim()
                            request({ publish(title, description, snapshot()) }) {
                                titleInput = TextFieldValue()
                                descriptionInput = TextFieldValue()
                                query = TextFieldValue()
                                appliedQuery = ""
                                entries = emptyList()
                                hasMore = false
                                loaded = false
                                mine = false
                                mode = CatalogMode.BROWSE
                                status = R.string.community_published
                                refreshOnOpen = true
                            }
                        }, Modifier.fillMaxWidth(), enabled = !busy && titleInput.text.isNotBlank())
                    }
                }
                if (mode != CatalogMode.BROWSE) {
                    CouiAction(stringResource(R.string.community_back_to_catalog), false, {
                        focus.clearFocus()
                        status = 0
                        mode = CatalogMode.BROWSE
                    }, Modifier.fillMaxWidth(), enabled = !busy)
                }
            }
        }

        if (mode == CatalogMode.BROWSE) {
            LaunchedEffect(refreshOnOpen) {
                if (refreshOnOpen) {
                    refreshOnOpen = false
                    refresh(success = status)
                }
            }
            if (email != null) {
                CouiAction(stringResource(if (mine) R.string.community_browse_all else R.string.community_mine),
                    false, { refresh(own = !mine) }, Modifier.fillMaxWidth(), enabled = !busy)
            }
            if (loaded && entries.isEmpty() && !busy) {
                Text(stringResource(R.string.community_empty), modifier = Modifier.padding(horizontal = 16.dp))
            }
            if (entries.isNotEmpty()) {
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                    for (entry in entries) {
                        BasicComponent(
                            title = entry.title,
                            summary = listOfNotNull("@${entry.author}", entry.description.takeIf(String::isNotBlank))
                                .joinToString("\n"),
                            onClick = if (busy) null else ({ request({ download(entry.id) }, onPreview) }),
                        )
                        if (mine) {
                            BasicComponent(title = stringResource(R.string.community_remove),
                                onClick = if (busy) null else ({
                                    removeEntry = entry
                                    showRemoval = true
                                }))
                        }
                    }
                }
            }
            if (hasMore) {
                CouiAction(stringResource(R.string.community_more), false, { refresh(append = true) },
                    Modifier.fillMaxWidth(), enabled = !busy)
            }
        }
    }

    removeEntry?.let { entry ->
        CouiConfirmation(showRemoval, stringResource(R.string.community_remove),
            stringResource(R.string.community_remove_hint, entry.title),
            stringResource(android.R.string.cancel), stringResource(R.string.community_remove_action),
            onDismissRequest = { showRemoval = false }, onNegative = { showRemoval = false },
            onPositive = {
                showRemoval = false
                request({ remove(entry.id) }) { entries = entries.filterNot { it.id == entry.id } }
            }, onDismissFinished = { removeEntry = null }, content = null)
    }
}

@Composable
private fun CatalogField(
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    label: Int,
    limit: Int,
    keyboard: KeyboardType = KeyboardType.Text,
    multiline: Boolean = false,
) {
    TextField(value = value, onValueChange = { if (it.text.length <= limit) onChange(it) },
        modifier = Modifier.fillMaxWidth(), backgroundMode = TextFieldMode.Rectangle,
        cornerRadius = 16.dp, label = stringResource(label), singleLine = !multiline,
        maxLines = if (multiline) 4 else 1, keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}
