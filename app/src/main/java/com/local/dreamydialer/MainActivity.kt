package com.local.dreamydialer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CallLog
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

enum class DialerTab {
    CONTACTS, RECENTS, DIALPAD
}

data class ActiveCallSession(
    val name: String,
    val number: String,
    val label: String,
    val photo: ImageBitmap?
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialPhone = intent?.data?.schemeSpecificPart.orEmpty()
        setContent {
            DreamyDialerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = LavenderBg
                ) {
                    DreamyDialerApp(initialDialNumber = initialPhone)
                }
            }
        }
    }
}

@Composable
fun DreamyDialerApp(initialDialNumber: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { LocalDialerRepository(context) }

    val requiredPermissions = remember {
        arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CALL_PHONE
        )
    }

    var permissionsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    var currentTab by remember {
        mutableStateOf(if (initialDialNumber.isNotEmpty()) DialerTab.DIALPAD else DialerTab.CONTACTS)
    }
    var dialNumber by remember { mutableStateOf(initialDialNumber) }
    var searchQuery by remember { mutableStateOf("") }

    var contacts by remember { mutableStateOf<List<DeviceContact>>(emptyList()) }
    var recents by remember { mutableStateOf<List<RecentCallEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    val photoCache = remember { mutableStateMapOf<Long, ImageBitmap?>() }
    var showAddContactDialog by remember { mutableStateOf(false) }
    var activeCallSession by remember { mutableStateOf<ActiveCallSession?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results[Manifest.permission.READ_CONTACTS] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun reloadAllData() {
        scope.launch {
            isLoading = true
            val loadedContacts = withContext(Dispatchers.IO) { repository.loadContacts() }
            val loadedRecents = withContext(Dispatchers.IO) { repository.loadRecentCalls() }
            contacts = loadedContacts
            recents = loadedRecents
            isLoading = false
        }
    }

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) {
            reloadAllData()
        } else {
            permLauncher.launch(requiredPermissions)
        }
    }

    fun startCallFlow(name: String, number: String, label: String, photo: ImageBitmap?) {
        val cleanNum = number.trim()
        if (cleanNum.isEmpty()) return
        activeCallSession = ActiveCallSession(
            name = name.ifBlank { cleanNum },
            number = cleanNum,
            label = label.ifBlank { "Mobile" },
            photo = photo
        )
        repository.placeDirectCall(cleanNum)
    }

    if (activeCallSession != null) {
        BackHandler { activeCallSession = null }
        AestheticCallingScreen(
            session = activeCallSession!!,
            onEndCall = {
                activeCallSession = null
                reloadAllData()
            }
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LavenderBg)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            when (currentTab) {
                DialerTab.CONTACTS -> {
                    ContactsCarouselScreen(
                        contacts = contacts,
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        isLoading = isLoading,
                        hasPermission = permissionsGranted,
                        photoCache = photoCache,
                        repository = repository,
                        onRequestPermission = { permLauncher.launch(requiredPermissions) },
                        onRefresh = { reloadAllData() },
                        onCallContact = { contact ->
                            val cached = photoCache[contact.id]
                            startCallFlow(
                                name = contact.name,
                                number = contact.phoneNumber,
                                label = contact.label,
                                photo = cached
                            )
                        }
                    )
                }

                DialerTab.RECENTS -> {
                    RecentsListScreen(
                        recents = recents,
                        onRefresh = { reloadAllData() },
                        onCallRecent = { entry ->
                            startCallFlow(
                                name = entry.name,
                                number = entry.number,
                                label = "Recent",
                                photo = null
                            )
                        }
                    )
                }

                DialerTab.DIALPAD -> {
                    DialpadScreen(
                        number = dialNumber,
                        contacts = contacts,
                        onDigitClick = { digit -> dialNumber += digit },
                        onBackspace = {
                            if (dialNumber.isNotEmpty()) {
                                dialNumber = dialNumber.dropLast(1)
                            }
                        },
                        onClearAll = { dialNumber = "" },
                        onCallClick = {
                            if (dialNumber.isNotBlank()) {
                                val matched = contacts.find {
                                    it.phoneNumber.replace(Regex("[^0-9]"), "")
                                        .endsWith(dialNumber.replace(Regex("[^0-9]"), ""))
                                }
                                startCallFlow(
                                    name = matched?.name ?: dialNumber,
                                    number = dialNumber,
                                    label = matched?.label ?: "Mobile",
                                    photo = matched?.let { photoCache[it.id] }
                                )
                            }
                        },
                        onAddContactClick = { showAddContactDialog = true }
                    )
                }
            }

            // Floating Periwinkle '+' Button on Contacts & Recents
            if (currentTab != DialerTab.DIALPAD) {
                FloatingActionButton(
                    onClick = { showAddContactDialog = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 22.dp, bottom = 92.dp)
                        .size(56.dp),
                    shape = CircleShape,
                    containerColor = PeriwinklePrimary,
                    contentColor = LavenderSurface
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Contact",
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            // Bottom Floating Capsule Navigation Bar (Contacts / Recents / Dialpad)
            BottomCapsuleNavBar(
                selectedTab = currentTab,
                onSelectTab = { currentTab = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            )
        }
    }

    if (showAddContactDialog) {
        AddContactDialog(
            initialPhone = if (currentTab == DialerTab.DIALPAD) dialNumber else "",
            onDismiss = { showAddContactDialog = false },
            onSave = { newName, newPhone ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        repository.saveNewContact(newName, newPhone)
                    }
                    if (ok) {
                        Toast.makeText(context, "Contact saved locally", Toast.LENGTH_SHORT).show()
                        showAddContactDialog = false
                        reloadAllData()
                    } else {
                        Toast.makeText(context, "Allow Contacts permission to save", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
}

@Composable
fun ContactsCarouselScreen(
    contacts: List<DeviceContact>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    isLoading: Boolean,
    hasPermission: Boolean,
    photoCache: MutableMap<Long, ImageBitmap?>,
    repository: LocalDialerRepository,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onCallContact: (DeviceContact) -> Unit
) {
    val filteredContacts = remember(contacts, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) contacts else contacts.filter {
            it.name.lowercase().contains(q) || it.phoneNumber.contains(q)
        }
    }

    val listState = rememberLazyListState()
    val snapBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val density = LocalDensity.current
    var showMenu by remember { mutableStateOf(false) }

    val centeredIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            layoutInfo.visibleItemsInfo.minByOrNull { item ->
                abs((item.offset + item.size / 2) - viewportCenter)
            }?.index ?: 0
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Bar matching Dreamy Lavender design
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Contacts",
                color = InkPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp
            )

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = InkPrimary
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor = LavenderSurface
                ) {
                    DropdownMenuItem(
                        text = { Text("Refresh Contacts", color = InkPrimary) },
                        onClick = {
                            showMenu = false
                            onRefresh()
                        }
                    )
                }
            }
        }

        // Rounded Lavender Search Pill
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, tint = InkPrimary)
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = SlateMuted)
                    }
                }
            },
            placeholder = { Text("Search contacts...", color = SlateMuted, fontSize = 14.sp) },
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = LavenderSearchBg,
                unfocusedContainerColor = LavenderSearchBg,
                focusedBorderColor = PeriwinklePrimary,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = InkPrimary,
                unfocusedTextColor = InkPrimary
            )
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Main Curved Circular-Arc Contacts Carousel
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 78.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                !hasPermission -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = "Allow Contacts & Call Access",
                            color = InkPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Needed to display your phone contacts and place calls offline.",
                            color = SlateMuted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = onRequestPermission,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PeriwinklePrimary,
                                contentColor = LavenderSurface
                            )
                        ) {
                            Text("Grant Permission", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                isLoading -> {
                    CircularProgressIndicator(color = PeriwinklePrimary)
                }

                filteredContacts.isEmpty() -> {
                    Text(
                        text = "No contacts found",
                        color = SlateMuted,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                else -> {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val itemHeight = 88.dp
                        val verticalPad = ((maxHeight - itemHeight) / 2f).coerceAtLeast(0.dp)
                        val maxArcOffsetPx = with(density) { 112.dp.toPx() }
                        val halfViewportPx = with(density) { (maxHeight / 2f).toPx() }.coerceAtLeast(1f)

                        LazyColumn(
                            state = listState,
                            flingBehavior = snapBehavior,
                            contentPadding = PaddingValues(vertical = verticalPad),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(filteredContacts, key = { idx, c -> "${c.id}_${c.phoneNumber}_$idx" }) { index, contact ->
                                LaunchedEffect(contact.id) {
                                    if (!photoCache.containsKey(contact.id)) {
                                        photoCache[contact.id] = withContext(Dispatchers.IO) {
                                            repository.loadContactPhoto(contact.id, contact.photoUri)
                                        }
                                    }
                                }

                                val avatar = photoCache[contact.id]
                                val isCentered = index == centeredIndex

                                val arcTransform by remember {
                                    derivedStateOf {
                                        val info = listState.layoutInfo
                                        val itemInfo = info.visibleItemsInfo.find { it.index == index }
                                        if (itemInfo == null) {
                                            Triple(0f, 0f, 0.85f)
                                        } else {
                                            val vpCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                                            val itemCenter = itemInfo.offset + itemInfo.size / 2f
                                            val norm = ((itemCenter - vpCenter) / halfViewportPx).coerceIn(-1.25f, 1.25f)
                                            // Curves smoothly from right edge inward toward center-left
                                            val shiftX = (norm * norm) * maxArcOffsetPx
                                            val tiltDeg = norm * 16f
                                            val scaleVal = (1f - abs(norm) * 0.16f).coerceIn(0.80f, 1f)
                                            Triple(shiftX, tiltDeg, scaleVal)
                                        }
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(itemHeight)
                                        .padding(horizontal = 18.dp)
                                        .graphicsLayer {
                                            translationX = arcTransform.first
                                            rotationZ = arcTransform.second
                                            scaleX = arcTransform.third
                                            scaleY = arcTransform.third
                                        },
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (isCentered) {
                                        // Centered Elevated White Capsule with Halo Avatar + Call Button
                                        Surface(
                                            onClick = { onCallContact(contact) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .shadow(
                                                    elevation = 10.dp,
                                                    shape = CircleShape,
                                                    ambientColor = PeriwinklePrimary.copy(alpha = 0.15f),
                                                    spotColor = PeriwinklePrimary.copy(alpha = 0.20f)
                                                ),
                                            shape = CircleShape,
                                            color = LavenderSurface,
                                            border = BorderStroke(1.dp, HairlineLavender)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(start = 8.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(64.dp)
                                                        .clip(CircleShape)
                                                        .background(LavenderHalo.copy(alpha = 0.55f))
                                                        .padding(4.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    ContactAvatar(
                                                        name = contact.name,
                                                        bitmap = avatar,
                                                        size = 56.dp
                                                    )
                                                }

                                                Spacer(modifier = Modifier.width(12.dp))

                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = contact.name,
                                                        color = InkPrimary,
                                                        fontSize = 16.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = "${contact.label} • ${contact.phoneNumber}",
                                                        color = SlateMuted,
                                                        fontSize = 12.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }

                                                Spacer(modifier = Modifier.width(8.dp))

                                                Surface(
                                                    onClick = { onCallContact(contact) },
                                                    modifier = Modifier.size(44.dp),
                                                    shape = CircleShape,
                                                    color = LavenderPillLight
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            imageVector = Icons.Default.Call,
                                                            contentDescription = "Call ${contact.name}",
                                                            tint = InkPrimary,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        // Curved Arc Contact Disc + Name + Label
                                        Row(
                                            modifier = Modifier
                                                .clickable { onCallContact(contact) }
                                                .padding(start = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            ContactAvatar(
                                                name = contact.name,
                                                bitmap = avatar,
                                                size = 52.dp
                                            )
                                            Spacer(modifier = Modifier.width(14.dp))
                                            Column(modifier = Modifier.widthIn(max = 185.dp)) {
                                                Text(
                                                    text = contact.name,
                                                    color = InkPrimary.copy(alpha = 0.88f),
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = contact.label,
                                                    color = SlateMuted,
                                                    fontSize = 12.sp,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AestheticCallingScreen(
    session: ActiveCallSession,
    onEndCall: () -> Unit
) {
    var elapsedSec by remember { mutableIntStateOf(0) }
    var isMuted by remember { mutableStateOf(false) }
    var isSpeaker by remember { mutableStateOf(false) }

    LaunchedEffect(session) {
        while (true) {
            delay(1000L)
            elapsedSec++
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "haloPulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val timerText = remember(elapsedSec) {
        if (elapsedSec < 2) "Calling..." else String.format("%02d:%02d", elapsedSec / 60, elapsedSec % 60)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFECE9FA),
                        LavenderBg,
                        Color(0xFFE7E3F8)
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 24.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = LavenderSurface.copy(alpha = 0.75f),
                    border = BorderStroke(1.dp, HairlineLavender)
                ) {
                    Text(
                        text = timerText,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                        color = PeriwinklePrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Concentric Glowing Lavender Halo + Circular Portrait
                Box(
                    modifier = Modifier.size(196.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(184.dp)
                            .graphicsLayer {
                                scaleX = pulseScale
                                scaleY = pulseScale
                            }
                            .clip(CircleShape)
                            .background(PeriwinklePrimary.copy(alpha = 0.12f))
                    )
                    Box(
                        modifier = Modifier
                            .size(156.dp)
                            .clip(CircleShape)
                            .background(LavenderSurface.copy(alpha = 0.85f))
                            .border(2.dp, PeriwinklePrimary.copy(alpha = 0.35f), CircleShape)
                            .padding(6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ContactAvatar(
                            name = session.name,
                            bitmap = session.photo,
                            size = 140.dp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                Text(
                    text = session.name,
                    color = InkPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${session.label} • ${session.number}",
                    color = SlateMuted,
                    fontSize = 14.sp
                )
            }

            // Frosted-Glass Call Controls + Glowing End Call Pill
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 18.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(32.dp),
                    color = LavenderSurface.copy(alpha = 0.82f),
                    border = BorderStroke(1.dp, HairlineLavender)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                onClick = { isMuted = !isMuted },
                                modifier = Modifier.size(54.dp),
                                shape = CircleShape,
                                color = if (isMuted) PeriwinklePrimary else LavenderPillLight
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                        contentDescription = "Mute",
                                        tint = if (isMuted) LavenderSurface else InkPrimary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Mute", color = SlateMuted, fontSize = 12.sp)
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                onClick = { isSpeaker = !isSpeaker },
                                modifier = Modifier.size(54.dp),
                                shape = CircleShape,
                                color = if (isSpeaker) PeriwinklePrimary else LavenderPillLight
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.VolumeUp,
                                        contentDescription = "Speaker",
                                        tint = if (isSpeaker) LavenderSurface else InkPrimary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Speaker", color = SlateMuted, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Surface(
                    onClick = onEndCall,
                    modifier = Modifier
                        .size(68.dp)
                        .shadow(10.dp, CircleShape, spotColor = EndRed.copy(alpha = 0.4f)),
                    shape = CircleShape,
                    color = EndRed
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = "End Call",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RecentsListScreen(
    recents: List<RecentCallEntry>,
    onRefresh: () -> Unit,
    onCallRecent: (RecentCallEntry) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Recents",
                color = InkPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold
            )
            TextButton(onClick = onRefresh) {
                Text("Refresh", color = PeriwinklePrimary, fontWeight = FontWeight.Bold)
            }
        }

        if (recents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("No recent calls", color = SlateMuted, fontSize = 15.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                items(recents, key = { it.id }) { entry ->
                    Surface(
                        onClick = { onCallRecent(entry) },
                        shape = RoundedCornerShape(22.dp),
                        color = LavenderSurface,
                        border = BorderStroke(1.dp, HairlineLavender)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val (typeIcon, typeTint) = when (entry.callType) {
                                CallLog.Calls.MISSED_TYPE -> Icons.AutoMirrored.Filled.CallMissed to EndRed
                                CallLog.Calls.OUTGOING_TYPE -> Icons.AutoMirrored.Filled.CallMade to PeriwinklePrimary
                                else -> Icons.AutoMirrored.Filled.CallReceived to CallGreen
                            }

                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(LavenderPillLight),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(typeIcon, contentDescription = null, tint = typeTint, modifier = Modifier.size(20.dp))
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.name,
                                    color = if (entry.callType == CallLog.Calls.MISSED_TYPE) EndRed else InkPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${entry.number} • ${entry.relativeTime}",
                                    color = SlateMuted,
                                    fontSize = 12.sp
                                )
                            }

                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = "Call",
                                tint = PeriwinklePrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DialpadScreen(
    number: String,
    contacts: List<DeviceContact>,
    onDigitClick: (String) -> Unit,
    onBackspace: () -> Unit,
    onClearAll: () -> Unit,
    onCallClick: () -> Unit,
    onAddContactClick: () -> Unit
) {
    val matchedContact = remember(number, contacts) {
        val clean = number.replace(Regex("[^0-9]"), "")
        if (clean.length < 3) null
        else contacts.find { it.phoneNumber.replace(Regex("[^0-9]"), "").contains(clean) }
    }

    val keys = listOf(
        listOf("1" to "", "2" to "ABC", "3" to "DEF"),
        listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
        listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
        listOf("*" to "", "0" to "+", "#" to "")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(bottom = 86.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 20.dp)
        ) {
            if (matchedContact != null) {
                Surface(
                    shape = CircleShape,
                    color = LavenderPillLight
                ) {
                    Text(
                        text = matchedContact.name,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                        color = PeriwinklePrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (number.isNotEmpty()) {
                TextButton(onClick = onAddContactClick) {
                    Text("+ Save to Contacts", color = PeriwinklePrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            } else {
                Spacer(modifier = Modifier.height(28.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = number.ifEmpty { "Enter number" },
                    color = if (number.isEmpty()) SlateMuted else InkPrimary,
                    fontSize = if (number.length > 12) 24.sp else 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                if (number.isNotEmpty()) {
                    IconButton(onClick = onBackspace) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Backspace,
                            contentDescription = "Delete",
                            tint = InkPrimary
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            keys.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    row.forEach { (digit, sub) ->
                        Surface(
                            onClick = { onDigitClick(digit) },
                            modifier = Modifier.size(72.dp),
                            shape = CircleShape,
                            color = LavenderSurface,
                            border = BorderStroke(1.dp, HairlineLavender)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = digit,
                                    color = InkPrimary,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (sub.isNotEmpty()) {
                                    Text(
                                        text = sub,
                                        color = SlateMuted,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    onClick = onCallClick,
                    modifier = Modifier
                        .size(68.dp)
                        .shadow(8.dp, CircleShape, spotColor = CallGreen.copy(alpha = 0.4f)),
                    shape = CircleShape,
                    color = CallGreen
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Place Call",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BottomCapsuleNavBar(
    selectedTab: DialerTab,
    onSelectTab: (DialerTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 10.dp,
                shape = CircleShape,
                ambientColor = PeriwinklePrimary.copy(alpha = 0.12f),
                spotColor = PeriwinklePrimary.copy(alpha = 0.16f)
            ),
        shape = CircleShape,
        color = LavenderSurface,
        border = BorderStroke(1.dp, HairlineLavender)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavBarItem(
                label = "Contacts",
                icon = Icons.Default.Person,
                selected = selectedTab == DialerTab.CONTACTS,
                onClick = { onSelectTab(DialerTab.CONTACTS) }
            )
            NavBarItem(
                label = "Recents",
                icon = Icons.Default.History,
                selected = selectedTab == DialerTab.RECENTS,
                onClick = { onSelectTab(DialerTab.RECENTS) }
            )
            NavBarItem(
                label = "Dialpad",
                icon = Icons.Default.Dialpad,
                selected = selectedTab == DialerTab.DIALPAD,
                onClick = { onSelectTab(DialerTab.DIALPAD) }
            )
        }
    }
}

@Composable
fun NavBarItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tint = if (selected) PeriwinklePrimary else SlateMuted
    Column(
        modifier = Modifier
            .clip(CircleShape)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
fun ContactAvatar(
    name: String,
    bitmap: ImageBitmap?,
    size: Dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(LavenderPillLight)
            .border(1.dp, HairlineLavender, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            val initial = name.firstOrNull()?.uppercase() ?: "?"
            Text(
                text = initial,
                color = PeriwinklePrimary,
                fontSize = (size.value * 0.38f).sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

@Composable
fun AddContactDialog(
    initialPhone: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf(initialPhone) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LavenderSurface,
        title = { Text("New Local Contact", color = InkPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") }
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    singleLine = true,
                    label = { Text("Phone Number") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && phone.isNotBlank()) {
                    onSave(name.trim(), phone.trim())
                }
            }) {
                Text("Save", color = PeriwinklePrimary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = SlateMuted)
            }
        }
    )
}
