package com.walkman.classic

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WalkmanApp() }
    }
}

enum class Screen { Home, Library, Favs, Now }

class Ui(val p: Player) : Player.Listener {
    var playing by mutableStateOf(p.isPlaying)
    var mediaId by mutableStateOf(p.currentMediaItem?.mediaId)
    var shuffle by mutableStateOf(p.shuffleModeEnabled)
    var repeat by mutableIntStateOf(p.repeatMode)
    var pos by mutableLongStateOf(0L)
    var dur by mutableLongStateOf(0L)
    var seeking by mutableStateOf(false)
    init { p.addListener(this) }
    override fun onEvents(player: Player, events: Player.Events) {
        playing = player.isPlaying
        mediaId = player.currentMediaItem?.mediaId
        shuffle = player.shuffleModeEnabled
        repeat = player.repeatMode
        dur = player.duration.coerceAtLeast(0)
    }
}

private val Bg = Color(0xFF111111)
private val Panel = Color(0xFF1E1E1E)
private val artCache = LruCache<Long, Bitmap>(60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkmanApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    }
    val askPerms = { launcher.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(perm, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(perm)) }
    LaunchedEffect(Unit) { if (!granted) askPerms() }

    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var scan by remember { mutableIntStateOf(0) }
    LaunchedEffect(granted, scan) { if (granted) songs = withContext(Dispatchers.IO) { loadSongs(ctx) } }

    var ui by remember { mutableStateOf<Ui?>(null) }
    DisposableEffect(Unit) {
        val f = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))).buildAsync()
        f.addListener({ ui = Ui(f.get()) }, ContextCompat.getMainExecutor(ctx))
        onDispose { MediaController.releaseFuture(f) }
    }
    LaunchedEffect(ui) {
        while (true) {
            ui?.let { if (!it.seeking) it.pos = it.p.currentPosition; it.dur = it.p.duration.coerceAtLeast(0) }
            delay(400)
        }
    }

    val prefs = remember { ctx.getSharedPreferences("walkman", 0) }
    var favs by remember { mutableStateOf(prefs.getStringSet("fav", emptySet())!!.toSet()) }
    var lastId by remember { mutableStateOf(prefs.getString("last", null)) }
    fun toggleFav(id: Long) {
        val k = id.toString()
        favs = if (k in favs) favs - k else favs + k
        prefs.edit().putStringSet("fav", favs).apply()
    }

    var screen by remember { mutableStateOf(Screen.Home) }
    var query by remember { mutableStateOf<String?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    BackHandler(screen != Screen.Home) { screen = if (screen == Screen.Now) Screen.Home else Screen.Home }

    fun play(list: List<Song>, i: Int) {
        ui?.p?.run { setMediaItems(list.map { it.item() }, i, 0); prepare(); play() }
        lastId = list[i].id.toString(); prefs.edit().putString("last", lastId).apply()
        screen = Screen.Now
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        ModalNavigationDrawer(
            drawerState = drawer, gesturesEnabled = screen != Screen.Now,
            drawerContent = {
                ModalDrawerSheet(drawerContainerColor = Panel) {
                    Text("Walkman", fontSize = 26.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(24.dp))
                    listOf(Screen.Home to "Inicio", Screen.Library to "Biblioteca", Screen.Favs to "Favoritas").forEach { (s, t) ->
                        NavigationDrawerItem(label = { Text(t) }, selected = screen == s,
                            onClick = { screen = s; scope.launch { drawer.close() } },
                            modifier = Modifier.padding(horizontal = 12.dp))
                    }
                }
            }
        ) {
            val u = ui
            if (screen == Screen.Now && u != null) {
                NowPlaying(u, songs, (u.mediaId ?: "") in favs, ::toggleFav) { screen = Screen.Home }
            } else {
                Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
                    TopBar(onMenu = { scope.launch { drawer.open() } }, query = query,
                        onQuery = { query = it; if (it != null && screen == Screen.Home) screen = Screen.Library },
                        onRescan = { scan++ })
                    Box(Modifier.weight(1f)) {
                        when {
                            !granted -> Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                                Text("Walkman necesita acceso a tu música", color = Color.White)
                                Button(onClick = { askPerms() }, modifier = Modifier.padding(16.dp)) { Text("Permitir") }
                            }
                            songs.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No se encontró música", color = Color.Gray) }
                            screen == Screen.Library -> LibraryScreen(songs, favs, false, query, ::play)
                            screen == Screen.Favs -> LibraryScreen(songs, favs, true, query, ::play)
                            else -> HomeScreen(songs, songs.find { it.id.toString() == lastId } ?: songs.first(), ::play)
                        }
                    }
                    ui?.let { Mini(it, songs) { screen = Screen.Now } }
                }
            }
        }
    }
}

@Composable
fun TopBar(onMenu: () -> Unit, query: String?, onQuery: (String?) -> Unit, onRescan: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onMenu) { Icon(Icons.Default.Menu, null, tint = Color.White) }
        if (query == null) {
            Text("Walkman", Modifier.weight(1f).padding(start = 12.dp), color = Color.White, fontSize = 20.sp)
            IconButton({ onQuery("") }) { Icon(Icons.Default.Search, null, tint = Color.White) }
        } else {
            TextField(query, { onQuery(it) }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Buscar") },
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
            IconButton({ onQuery(null) }) { Icon(Icons.Default.Close, null, tint = Color.White) }
        }
        Box {
            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, null, tint = Color.White) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Reescanear música") }, onClick = { menu = false; onRescan() })
            }
        }
    }
}

@Composable
fun HomeScreen(songs: List<Song>, hero: Song, onPlay: (List<Song>, Int) -> Unit) {
    val albums = remember(songs) { songs.groupBy { it.albumId }.values.toList() }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Escuchar a continuación", fontSize = 30.sp, fontWeight = FontWeight.Light, color = Color.White,
            modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 12.dp))
        Box(Modifier.fillMaxWidth().aspectRatio(1.5f).clickable { onPlay(songs, songs.indexOf(hero)) }) {
            Art(hero, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.Center).size(76.dp).clip(CircleShape).background(Color.Black.copy(.55f)), Alignment.Center) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(48.dp))
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.85f)))).padding(14.dp)) {
                Text(hero.title, color = Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(hero.artist, color = Color.LightGray, fontSize = 14.sp, maxLines = 1)
            }
        }
        Text("Álbumes", fontSize = 22.sp, fontWeight = FontWeight.Light, color = Color.White, modifier = Modifier.padding(16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(albums) { a ->
                Column(Modifier.width(140.dp).clickable { onPlay(a, 0) }) {
                    Art(a[0], Modifier.size(140.dp))
                    Text(a[0].album, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Text(a[0].artist, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun LibraryScreen(songs: List<Song>, favs: Set<String>, favOnly: Boolean, query: String?, onPlay: (List<Song>, Int) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val base = songs.filter { s ->
        (!favOnly || s.id.toString() in favs) &&
            (query.isNullOrBlank() || listOf(s.title, s.artist, s.album).any { it.contains(query, true) })
    }
    Column {
        TabRow(tab, containerColor = Panel, contentColor = Color.White) {
            listOf("Canciones", "Álbumes", "Artistas").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> LazyColumn { itemsIndexed(base) { i, s ->
                Row(Modifier.fillMaxWidth().clickable { onPlay(base, i) }.padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Art(s, Modifier.size(48.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(s.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.artist, color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(fmt(s.duration), color = Color.Gray, fontSize = 12.sp)
                }
            } }
            1 -> {
                val groups = remember(base) { base.groupBy { it.albumId }.values.toList() }
                LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(groups) { g ->
                        Column(Modifier.clickable { onPlay(g, 0) }) {
                            Art(g[0], Modifier.fillMaxWidth().aspectRatio(1f))
                            Text(g[0].album, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                            Text(g[0].artist, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
            else -> {
                val groups = remember(base) { base.groupBy { it.artist }.toList() }
                LazyColumn { items(groups) { (name, list) ->
                    Row(Modifier.fillMaxWidth().clickable { onPlay(list, 0) }.padding(16.dp, 12.dp)) {
                        Text(name, color = Color.White, modifier = Modifier.weight(1f))
                        Text("${list.size}", color = Color.Gray)
                    }
                } }
            }
        }
    }
}

@Composable
fun Mini(ui: Ui, songs: List<Song>, onOpen: () -> Unit) {
    val cur = songs.find { it.id.toString() == ui.mediaId } ?: return
    Row(Modifier.fillMaxWidth().background(Panel).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
        Art(cur, Modifier.size(56.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(cur.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(cur.artist, color = Color.Gray, fontSize = 13.sp, maxLines = 1)
        }
        Round(38.dp, Icons.Default.SkipPrevious) { ui.p.seekToPrevious() }
        Spacer(Modifier.width(8.dp))
        Round(44.dp, if (ui.playing) Icons.Default.Pause else Icons.Default.PlayArrow) { if (ui.playing) ui.p.pause() else ui.p.play() }
        Spacer(Modifier.width(8.dp))
        Round(38.dp, Icons.Default.SkipNext) { ui.p.seekToNext() }
        Spacer(Modifier.width(10.dp))
    }
}

@Composable
fun NowPlaying(ui: Ui, songs: List<Song>, fav: Boolean, onFav: (Long) -> Unit, onBack: () -> Unit) {
    val cur = songs.find { it.id.toString() == ui.mediaId }
    val next = if (ui.p.hasNextMediaItem()) ui.p.getMediaItemAt(ui.p.nextMediaItemIndex).mediaId.let { id -> songs.find { it.id.toString() == id } } else null
    var base by remember { mutableStateOf(Color(0xFF263238)) }
    val bg by animateColorAsState(base, label = "bg")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(bg, Color.Black))).systemBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(Modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
                Text("Walkman", color = Color.White, fontSize = 20.sp)
            }
            Text(cur?.title ?: "", color = Color.White, fontSize = 30.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(cur?.artist ?: "", color = Color.White.copy(.85f), fontSize = 18.sp, maxLines = 1)
            Text(cur?.album ?: "", color = Color.White.copy(.6f), fontSize = 14.sp, maxLines = 1)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val side = minOf(maxWidth * 0.85f, maxHeight)
                    if (next != null) Art(next, Modifier.size(side * 0.9f).align(Alignment.CenterEnd))
                    Art(cur, Modifier.size(side).align(Alignment.CenterStart).shadow(12.dp), onBitmap = { base = tint(it) })
                }
                Column(Modifier.width(56.dp).fillMaxHeight(), Arrangement.SpaceEvenly, Alignment.CenterHorizontally) {
                    IconButton({ cur?.let { onFav(it.id) } }) {
                        Icon(if (fav) Icons.Default.Star else Icons.Default.StarBorder, null, tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    IconButton({ ui.p.shuffleModeEnabled = !ui.shuffle }) {
                        Icon(Icons.Default.Shuffle, null, tint = Color.White.copy(if (ui.shuffle) 1f else .45f), modifier = Modifier.size(28.dp))
                    }
                    IconButton({ ui.p.repeatMode = when (ui.repeat) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } }) {
                        Icon(if (ui.repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, null,
                            tint = Color.White.copy(if (ui.repeat == Player.REPEAT_MODE_OFF) .45f else 1f), modifier = Modifier.size(28.dp))
                    }
                }
            }
            Slider(
                value = if (ui.dur > 0) (ui.pos.toFloat() / ui.dur).coerceIn(0f, 1f) else 0f,
                onValueChange = { ui.seeking = true; ui.pos = (it * ui.dur).toLong() },
                onValueChangeFinished = { ui.p.seekTo(ui.pos); ui.seeking = false },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(.3f))
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text(fmt(ui.pos), color = Color.White.copy(.7f), fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(fmt(ui.dur), color = Color.White.copy(.7f), fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
                Round(58.dp, Icons.Default.SkipPrevious) { ui.p.seekToPrevious() }
                Round(78.dp, if (ui.playing) Icons.Default.Pause else Icons.Default.PlayArrow) { if (ui.playing) ui.p.pause() else ui.p.play() }
                Round(58.dp, Icons.Default.SkipNext) { ui.p.seekToNext() }
            }
        }
    }
}

@Composable
fun Round(size: Dp, icon: ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(size).border(2.dp, Color.White, CircleShape).clip(CircleShape).clickable(onClick = onClick), Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

fun tint(b: Bitmap?): Color {
    if (b == null) return Color(0xFF263238)
    val px = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0)
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(px, hsv)
    hsv[1] = hsv[1].coerceIn(.55f, .9f); hsv[2] = .55f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

suspend fun loadArt(ctx: android.content.Context, s: Song): Bitmap? =
    artCache.get(s.albumId) ?: withContext(Dispatchers.IO) {
        try { ctx.contentResolver.loadThumbnail(s.uri, Size(512, 512), null).also { artCache.put(s.albumId, it) } }
        catch (e: Exception) { null }
    }

@Composable
fun Art(s: Song?, modifier: Modifier = Modifier, onBitmap: (Bitmap?) -> Unit = {}) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, s?.albumId) { value = s?.let { loadArt(ctx, it) } }
    LaunchedEffect(bmp) { onBitmap(bmp) }
    Box(modifier.background(Color(0xFF333333)), Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.MusicNote, null, tint = Color.Gray)
    }
}
