package com.cosmod.launcherglass.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.community.CommunityAccount
import com.cosmod.launcherglass.community.CommunityApi
import com.cosmod.launcherglass.community.CommunityOAuth
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.FloatingActionButton
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.InputField
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.CardListGroup
import io.github.suqi8.coui.kmp.basic.SegmentButton
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.SearchBar
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.window.WindowDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class CatalogFilter(val label: Int) {
    ALL(R.string.community_filter_all),
    MINE(R.string.community_mine),
    FAVORITES(R.string.community_filter_favorites),
}

@Composable
internal fun CommunityCatalog(
    snapshot: () -> String,
    onPreview: (SettingsTransfer.Document) -> Unit,
    onAccount: () -> Unit,
) {
    if (!CommunityAccount.configured) {
        CommunityUnavailable()
        return
    }

    val focus = LocalFocusManager.current
    val request = rememberCommunityRequest()
    var query by remember { mutableStateOf("") }
    var loadedQuery by remember { mutableStateOf<String?>(null) }
    var catalogFilter by remember { mutableStateOf(CatalogFilter.ALL) }
    val mine = catalogFilter == CatalogFilter.MINE
    val favorites = catalogFilter == CatalogFilter.FAVORITES
    var signedIn by remember { mutableStateOf(false) }
    var canPin by remember { mutableStateOf(false) }
    var supportsReactions by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<CommunityApi.Entry>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var removeEntry by remember { mutableStateOf<CommunityApi.Entry?>(null) }
    var showRemoval by remember { mutableStateOf(false) }
    var showPublish by remember { mutableStateOf(false) }
    var showAccountRequired by remember { mutableStateOf(false) }
    var nickname by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val filter = query.trim()

    LaunchedEffect(Unit) {
        try {
            signedIn = withContext(Dispatchers.IO) { CommunityAccount.get(context).identity != null }
            if (signedIn) canPin = withContext(Dispatchers.IO) { CommunityAccount.get(context).canPin() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            request.status = communityError(error)
        }
    }
    LaunchedEffect(filter, catalogFilter, refresh) {
        if (filter.isNotEmpty()) delay(320)
        searchLoading = true
        searchError = 0
        try {
            val page = withContext(Dispatchers.IO) {
                CommunityAccount.get(context).search(filter, null, mine, favorites)
            }
            entries = page.entries
            supportsReactions = page.supportsReactions
            hasMore = page.entries.size == CommunityApi.PAGE_SIZE
            loadedQuery = filter
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            searchError = communityError(error)
        } finally {
            searchLoading = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SearchBar(
                inputField = {
                    InputField(
                        query = query,
                        onQueryChange = { query = it.take(80) },
                        onSearch = { focus.clearFocus(); refresh++ },
                        expanded = query.isNotEmpty(),
                        onExpandedChange = { if (!it) focus.clearFocus() },
                        label = stringResource(R.string.community_search),
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                onExpandedChange = { if (!it) focus.clearFocus() },
                insideMargin = DpSize.Zero,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {}
            Spacer(Modifier.height(12.dp))
            if (signedIn) SegmentButton(
                items = CatalogFilter.entries.filter { supportsReactions || it != CatalogFilter.FAVORITES }
                    .map { stringResource(it.label) },
                selectedIndex = catalogFilter.ordinal,
                onSelectedIndexChange = { index ->
                    if (index != catalogFilter.ordinal && !request.busy) {
                        catalogFilter = CatalogFilter.entries[index]
                        entries = emptyList()
                        loadedQuery = null
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                enabled = !request.busy,
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if ((searchLoading || request.busy) && entries.isEmpty()) item { CommunityLoading() }
                val message = request.status.takeIf { it != 0 } ?: searchError.takeIf { it != 0 }
                when {
                    message != null -> item {
                        BasicComponent(title = stringResource(message),
                            onClick = if (searchError == 0) null else ({ refresh++ }))
                    }
                    loadedQuery == filter && entries.isEmpty() && !searchLoading -> item {
                        BasicComponent(title = stringResource(R.string.community_empty))
                    }
                }
                items(entries, key = { it.id }) { entry ->
                    val enabled = !request.busy && !searchLoading && loadedQuery == filter
                    CatalogEntry(
                        entry, enabled, canPin, mine,
                        onPreview = {
                            focus.clearFocus()
                            request.run({ download(entry.id) }, onPreview)
                        },
                        onReaction = { reaction, selected ->
                            if (!signedIn) {
                                showAccountRequired = true
                            } else {
                                request.run({ setReaction(entry.id, reaction, selected) }) { reactions ->
                                    entries = entries.map { if (it.id == entry.id) it.copy(reactions = reactions) else it }
                                    if (favorites && !reactions.favorited) refresh++
                                }
                            }
                        },
                        onPin = { request.run({ setPin(entry.id, !entry.pinned) }) { refresh++ } },
                        onRemove = { removeEntry = entry; showRemoval = true },
                    )
                }
                if (hasMore && loadedQuery == filter && entries.isNotEmpty()) item {
                    CouiAction(stringResource(R.string.community_more), false, {
                        val cursor = entries.last()
                        val scope = catalogFilter
                        request.run({ search(filter, cursor, scope == CatalogFilter.MINE, scope == CatalogFilter.FAVORITES) }) { page ->
                            if (query.trim() == filter && catalogFilter == scope && entries.lastOrNull() == cursor) {
                                entries = (entries + page.entries).distinctBy { it.id }
                                hasMore = page.entries.size == CommunityApi.PAGE_SIZE
                            }
                        }
                    }, Modifier.fillMaxWidth(), enabled = !request.busy && !searchLoading)
                }
            }
        }
        if ((searchLoading || request.busy) && entries.isNotEmpty()) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 22.dp, bottom = 24.dp),
                size = 28.dp,
            )
        }
        FloatingActionButton(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
            enabled = !request.busy,
            visible = !showPublish && !showAccountRequired,
            material = true,
            onClick = {
                focus.clearFocus()
                request.run({ name() }) { publicName ->
                    nickname = publicName
                    if (publicName == null) showAccountRequired = true else showPublish = true
                }
            },
        ) {
            Icon(
                imageVector = COUIIcons.Add,
                contentDescription = stringResource(R.string.community_publish),
                modifier = Modifier.size(24.dp),
            )
        }
    }
    WindowDialog(
        show = showAccountRequired,
        title = stringResource(R.string.community_account),
        summary = stringResource(R.string.community_sign_in_required),
        onDismissRequest = { showAccountRequired = false },
    ) {
        CouiAction(stringResource(R.string.community_open_account), true, {
            showAccountRequired = false
            onAccount()
        }, Modifier.fillMaxWidth())
    }
    PublishConfigDialog(showPublish, nickname.orEmpty(), snapshot, { showPublish = false }) {
        showPublish = false
        request.status = R.string.community_published
        catalogFilter = CatalogFilter.ALL
        query = ""
        refresh++
    }
    removeEntry?.let { entry ->
        CouiConfirmation(showRemoval, stringResource(R.string.community_remove),
            stringResource(R.string.community_remove_hint, entry.title),
            stringResource(android.R.string.cancel), stringResource(R.string.community_remove_action),
            onDismissRequest = { showRemoval = false }, onNegative = { showRemoval = false },
            onPositive = {
                showRemoval = false
                request.run({ remove(entry.id) }) { entries = entries.filterNot { it.id == entry.id } }
            }, onDismissFinished = { removeEntry = null }, content = null)
    }
}

@Composable
private fun CatalogEntry(
    entry: CommunityApi.Entry,
    enabled: Boolean,
    canPin: Boolean,
    canRemove: Boolean,
    onPreview: () -> Unit,
    onReaction: (CommunityApi.Reaction, Boolean) -> Unit,
    onPin: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        CardListGroup {
            item {
                ArrowPreference(
                    title = entry.title,
                    summary = listOfNotNull(
                        stringResource(R.string.community_pinned).takeIf { entry.pinned },
                        "@${entry.author}",
                        entry.description.takeIf(String::isNotBlank),
                    ).joinToString("\n"),
                    enabled = enabled,
                    onClick = onPreview,
                )
            }
        }
        entry.reactions?.let { reactions ->
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CatalogReaction(
                    if (reactions.liked) "♥" else "♡", reactions.likes, reactions.liked, enabled,
                    stringResource(if (reactions.liked) R.string.community_unlike else R.string.community_like),
                ) { onReaction(CommunityApi.Reaction.LIKE, !reactions.liked) }
                CatalogReaction(
                    if (reactions.favorited) "★" else "☆", reactions.favorites, reactions.favorited, enabled,
                    stringResource(if (reactions.favorited) R.string.community_unfavorite else R.string.community_favorite),
                ) { onReaction(CommunityApi.Reaction.FAVORITE, !reactions.favorited) }
            }
        }
        if (canPin || canRemove) CardListGroup {
            if (canPin) item {
                BasicComponent(
                    title = stringResource(if (entry.pinned) R.string.community_unpin else R.string.community_pin),
                    enabled = enabled,
                    onClick = onPin,
                )
            }
            if (canRemove) item {
                BasicComponent(title = stringResource(R.string.community_remove), enabled = enabled, onClick = onRemove)
            }
        }
    }
}

@Composable
private fun CatalogReaction(
    icon: String, count: Long, active: Boolean, enabled: Boolean, label: String, onClick: () -> Unit,
) {
    TextButton(
        text = "$icon  $count",
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = "$label, $count"; selected = active },
        enabled = enabled,
        minHeight = 40.dp,
        colors = if (active) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
    )
}

@Composable
internal fun CommunityLoading(label: Int = R.string.community_working) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(size = 22.dp, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(label), color = COUITheme.colorScheme.labelSecondary, style = COUITheme.textStyles.bodyM)
    }
}

@Composable
internal fun CommunityAccountScreen(signInResult: CommunityOAuth.Result?, onSignInHandled: () -> Unit) {
    if (!CommunityAccount.configured) {
        CommunityUnavailable()
        return
    }
    val request = rememberCommunityRequest()
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    var email by remember { mutableStateOf<String?>(null) }
    var nickname by remember { mutableStateOf(TextFieldValue()) }
    var accountChecked by remember { mutableStateOf(false) }
    LaunchedEffect(signInResult) {
        if (signInResult == null && accountChecked) return@LaunchedEffect
        accountChecked = true
        if (signInResult != null) onSignInHandled()
        when (signInResult) {
            CommunityOAuth.Result.CANCELLED -> request.status = R.string.community_github_cancelled
            CommunityOAuth.Result.FAILED -> request.status = R.string.community_github_failed
            else -> request.run({ identity?.let { it to name() } }) { account ->
                email = account?.first
                nickname = TextFieldValue(account?.second.orEmpty())
            }
        }
    }
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val account = email
            if (account == null) {
                Text(stringResource(R.string.community_github_hint))
                CouiAction(stringResource(R.string.community_github_sign_in), true, {
                    request.run({ beginGitHubSignIn() }) { url ->
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                }, Modifier.fillMaxWidth(), enabled = !request.busy)
            } else {
                Text(account.ifBlank { stringResource(R.string.community_github_connected) })
                CatalogField(nickname, { nickname = it }, R.string.community_name, 24)
                Text(stringResource(R.string.community_name_hint), color = COUITheme.colorScheme.labelSecondary)
                val name = nickname.text.trim()
                CouiAction(stringResource(R.string.community_save_name), true, {
                    focus.clearFocus()
                    request.run({ setName(name) }) { request.status = R.string.community_saved }
                }, Modifier.fillMaxWidth(), enabled = !request.busy && CommunityApi.NICKNAME.matches(name))
                CouiAction(stringResource(R.string.community_sign_out), false, {
                    focus.clearFocus()
                    request.run({ signOut() }) { email = null; nickname = TextFieldValue() }
                }, Modifier.fillMaxWidth(), enabled = !request.busy)
            }
            if (request.busy) CommunityLoading()
            else if (request.status != 0) Text(stringResource(request.status))
        }
    }
}

@Composable
private fun PublishConfigDialog(
    show: Boolean,
    nickname: String,
    snapshot: () -> String,
    onDismiss: () -> Unit,
    onPublished: () -> Unit,
) {
    var title by remember { mutableStateOf(TextFieldValue()) }
    var description by remember { mutableStateOf(TextFieldValue()) }
    val request = rememberCommunityRequest()
    val name = title.text.trim()
    val dismiss = {
        if (!request.busy) {
            request.status = 0
            onDismiss()
        }
    }

    CouiConfirmation(
        show = show,
        title = stringResource(R.string.community_publish),
        summary = stringResource(R.string.community_publish_hint, nickname),
        negativeText = stringResource(android.R.string.cancel),
        positiveText = stringResource(R.string.community_publish_action),
        onDismissRequest = dismiss,
        onNegative = dismiss,
        onPositive = {
            val details = description.text.trim()
            request.run({ publish(name, details, snapshot()) }) {
                title = TextFieldValue()
                description = TextFieldValue()
                onPublished()
            }
        },
        onDismissFinished = {},
        positiveEnabled = !request.busy && name.isNotEmpty(),
        negativeEnabled = !request.busy,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CatalogField(title, { title = it }, R.string.community_config_name,
                    SettingsTransfer.TITLE_LIMIT, enabled = !request.busy)
                CatalogField(description, { description = it }, R.string.community_description,
                    SettingsTransfer.DESCRIPTION_LIMIT, multiline = true, enabled = !request.busy)
                if (request.busy) CommunityLoading()
                else if (request.status != 0) {
                    Text(stringResource(request.status), color = COUITheme.colorScheme.labelSecondary,
                        style = COUITheme.textStyles.bodyM)
                }
            }
        },
    )
}

@Composable
private fun CommunityUnavailable() {
    Card(Modifier.fillMaxWidth()) {
        BasicComponent(title = stringResource(R.string.community_unavailable),
            summary = stringResource(R.string.community_unavailable_hint))
    }
}

@Composable
private fun rememberCommunityRequest(): CommunityRequest {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember(scope, context) { CommunityRequest(scope, context.applicationContext) }
}

private class CommunityRequest(
    private val scope: CoroutineScope,
    private val context: Context,
) {
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf(0)

    fun <T> run(work: CommunityAccount.() -> T, onResult: (T) -> Unit) {
        if (busy) return
        status = 0
        busy = true
        scope.launch {
            try {
                onResult(withContext(Dispatchers.IO) { CommunityAccount.get(context).work() })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status = communityError(error)
            } finally {
                busy = false
            }
        }
    }
}

private fun communityError(error: Exception): Int = when {
    error !is CommunityApi.Failure -> R.string.community_error
    error.code == "23505" -> R.string.community_name_taken
    error.status == 429 -> R.string.community_rate_limited
    error.status in 401..403 -> R.string.community_sign_in_required
    error.status == 404 -> R.string.community_missing_entry
    else -> R.string.community_error
}

@Composable
private fun CatalogField(
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    label: Int,
    limit: Int,
    multiline: Boolean = false,
    enabled: Boolean = true,
) {
    TextField(value = value, onValueChange = { if (it.text.length <= limit) onChange(it) },
        modifier = Modifier.fillMaxWidth(), backgroundMode = TextFieldMode.Rectangle,
        cornerRadius = 16.dp, label = stringResource(label), singleLine = !multiline, enabled = enabled,
        maxLines = if (multiline) 4 else 1)
}
