package com.example.mangareader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.mangareader.MangaApplication
import com.example.mangareader.data.CredentialsStore
import com.example.mangareader.data.WebDavCacheManager
import com.example.mangareader.data.WebDavMangaSource
import kotlinx.coroutines.launch

/**
 * WebDAV 配置页：一个账号一张卡片，可添加 / 修改 / 删除多个源。
 *
 * 目录约定与本地源一致：`根目录/作品名/章节`，章节为图片文件夹或 zip/cbz 压缩包。
 * 自建 NAS 走 HTTP 明文也行（Manifest 已开 usesCleartextTraffic）。
 *
 * **为什么要做成多账号**：以前整页只存一条凭证，作品 id 的前缀写死成 `dav`，
 * 想接第二个 WebDAV 只能先把旧的删掉。现在每个账号有自己的源 id，
 * 书架上可以按账号筛选，两个服务器上同名作品也不会互相顶掉。
 *
 * @param onConfigChanged 账号有增删后回调，外层据此重建源实例并重扫书架
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavSettingsScreen(
    onBack: () -> Unit,
    onConfigChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MangaApplication
    val credentialsStore = remember { CredentialsStore(app) }
    val scope = rememberCoroutineScope()

    var accounts by remember { mutableStateOf<List<CredentialsStore.DavAccount>>(emptyList()) }
    var drafts by remember { mutableStateOf<List<CredentialsStore.DavAccount>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        accounts = credentialsStore.accounts()
        drafts = accounts
    }

    /** 改草稿（不落盘）：只改内存里这一张卡，点「保存并测试」才真正落盘 */
    fun update(index: Int, account: CredentialsStore.DavAccount) {
        drafts = drafts.toMutableList().also { it[index] = account }
    }

    /** 保存一个账号并测连通性 */
    fun save(index: Int) {
        val draft = drafts[index]
        if (draft.url.isBlank()) {
            status = "请先填服务器地址"
            return
        }
        scope.launch {
            testing = true
            status = ""
            val result = runCatching {
                credentialsStore.saveAccount(draft.copy(url = draft.url.trim().trimEnd('/')))
                val source = WebDavMangaSource(
                    draft.url.trim().trimEnd('/'),
                    draft.username.trim(),
                    draft.password,
                    WebDavCacheManager.davCacheDir(app),
                    app,
                )
                // 加硬超时：服务端不回数据时不能让用户对着转圈干等。
                // 客户端超时之外再兜一层，超时文案要说清是「超时」，别笼统写成「连接失败」。
                val found = kotlinx.coroutines.withTimeout(45_000) {
                    source.getMangaList("")
                }
                "「${draft.name}」连接成功，根目录发现 ${found.size} 个作品"
            }
            status = result.getOrElse { error ->
                if (error is kotlinx.coroutines.TimeoutCancellationException) {
                    "连接超时：45 秒内没有收到服务器响应，请检查地址 / 账号，或服务端是否在限速"
                } else {
                    "连接失败：${error.message}"
                }
            }
            testing = false
            accounts = credentialsStore.accounts()
            drafts = accounts
            if (status.startsWith("「")) onConfigChanged()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WebDAV 同步") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (drafts.isEmpty()) {
                Text(
                    text = "还没有添加 WebDAV 账号。点下面的按钮，把服务器地址、账号、密码填进去就能扫描漫画。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            drafts.forEachIndexed { index, account ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (account.name.isBlank()) "WebDAV ${index + 1}" else account.name,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        OutlinedTextField(
                            value = account.name,
                            onValueChange = { update(index, account.copy(name = it)) },
                            label = { Text("名称（书架上显示的分类名）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = account.url,
                            onValueChange = { update(index, account.copy(url = it)) },
                            label = { Text("服务器地址") },
                            placeholder = { Text("https://dav.example.com/dav/manga/") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = account.username,
                            onValueChange = { update(index, account.copy(username = it)) },
                            label = { Text("账号") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = account.password,
                            onValueChange = { update(index, account.copy(password = it)) },
                            label = { Text("密码") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !testing,
                                onClick = { save(index) },
                                modifier = Modifier.weight(1f),
                            ) {
                                if (testing) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                }
                                Text("保存并测试")
                            }
                            TextButton(
                                enabled = !testing,
                                onClick = {
                                    scope.launch {
                                        credentialsStore.removeAccount(account.sourceId)
                                        accounts = credentialsStore.accounts()
                                        drafts = accounts
                                        status = "已删除「${account.name}」"
                                        onConfigChanged()
                                    }
                                },
                            ) { Text("删除") }
                        }
                    }
                }
            }

            Button(
                enabled = !testing,
                onClick = {
                    val fresh = CredentialsStore.DavAccount(
                        sourceId = CredentialsStore.newSourceId(),
                        name = "",
                        url = "",
                        username = "",
                        password = "",
                    )
                    drafts = drafts + fresh
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("+ 添加另一个 WebDAV") }

            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = "目录约定：根目录下「作品名/章节」，章节可以是图片文件夹或 zip/cbz 压缩包。" +
                    "压缩包章节首次打开会整包下载到缓存，之后秒开；文件夹章节直接流式读取。" +
                    "接入多个账号后，书架顶部可以按名称筛选只看其中某一个源。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
