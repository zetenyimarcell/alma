package com.hangfolyam.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.SoundEffectConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate as rotateModifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.OAuthProvider
import com.hangfolyam.app.audio.AudioRecorder
import com.hangfolyam.app.network.RecognitionApi
import com.hangfolyam.app.network.RecognitionResult
import com.hangfolyam.app.ui.ModernRecognitionScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.sin

private val sharedHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}

private const val GEMINI_API_KEY = "AQ.Ab8RN6LHnJ8PH8dPk6VFQyMGyyg1RmHAtQnoQIejjNWTWLsBbg"

// Erős, véletlenszerű jelszó a "Suggest strong" gombhoz - kis- és nagybetűket,
// számokat és szimbólumokat is kever, hogy garantáltan a legmagasabb
// (bank vault) szintet érje el.
private fun generateStrongPassword(length: Int = 18): String {
    val lower = "abcdefghijkmnpqrstuvwxyz"
    val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    val digits = "23456789"
    val symbols = "!@#$%^&*-_+="
    val all = lower + upper + digits + symbols
    val random = SecureRandom()

    val required = listOf(
        lower[random.nextInt(lower.length)],
        upper[random.nextInt(upper.length)],
        digits[random.nextInt(digits.length)],
        symbols[random.nextInt(symbols.length)]
    )
    val rest = (0 until (length - required.size)).map { all[random.nextInt(all.length)] }
    return (required + rest).shuffled(kotlin.random.Random(random.nextLong())).joinToString("")
}

class MainActivity : ComponentActivity() {
    private var exoPlayer: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF0F101A), surface = Color(0xFF1A1C29))) {
                AppNavigation(exoPlayer)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
    }
}

@Composable
fun AppNavigation(exoPlayer: ExoPlayer?) {
    var selectedTab by remember { mutableStateOf(0) }
    val auth = FirebaseAuth.getInstance()
    var currentUser by remember { mutableStateOf(auth.currentUser) }
    var showRegistration by remember { mutableStateOf(false) }

    if (currentUser == null) {
        if (showRegistration) {
            VaultRegistrationScreen(
                onBack = { showRegistration = false },
                onRegisterSuccess = { currentUser = auth.currentUser }
            )
        } else {
            LoginScreen(
                onLoginSuccess = { currentUser = auth.currentUser },
                onNavigateToRegister = { showRegistration = true }
            )
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Főoldal") },
                        label = { Text("Főoldal") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Search, contentDescription = "Kereső") },
                        label = { Text("Kereső") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.Mic, contentDescription = "Felismerő") },
                        label = { Text("Felismerő") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Default.Person, contentDescription = "Profil") },
                        label = { Text("Profil") }
                    )
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                when (selectedTab) {
                    0 -> HomeScreen(exoPlayer)
                    1 -> SearchScreen(exoPlayer)
                    2 -> AudioRecognizerScreen(exoPlayer)
                    3 -> ProfileScreen(onSignOut = {
                        auth.signOut()
                        exoPlayer?.stop()
                        currentUser = null
                    })
                }
            }
        }
    }
}

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit, onNavigateToRegister: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    val auth = FirebaseAuth.getInstance()

    val infiniteTransition = rememberInfiniteTransition(label = "login_pulse")
    val scaleAnim by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F101A))
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(90.dp)
                .scale(scaleAnim)
                .clip(CircleShape)
                .background(Color(0xFF6B4EE6).copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = Color(0xFF6B4EE6),
                modifier = Modifier.size(45.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            "Hangfolyam Vault",
            fontSize = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            fontFamily = FontFamily.Monospace
        )
        Text(
            "Biztonságos zenei élmény",
            fontSize = 14.sp,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1C29)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email cím") },
                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = Color.Gray) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6B4EE6),
                        unfocusedBorderColor = Color.DarkGray,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Jelszó") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = Color.Gray) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6B4EE6),
                        unfocusedBorderColor = Color.DarkGray,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        if (email.isNotEmpty() && password.isNotEmpty()) {
                            auth.signInWithEmailAndPassword(email, password)
                                .addOnSuccessListener { onLoginSuccess() }
                                .addOnFailureListener { errorMessage = it.localizedMessage ?: "Hiba történt" }
                        } else {
                            errorMessage = "Kérjük töltsd ki a mezőket!"
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6B4EE6))
                ) {
                    Text("Bejelentkezés", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = {
                if (activity != null) {
                    try {
                        val provider = OAuthProvider.newBuilder("google.com")
                        provider.addCustomParameter("prompt", "select_account")
                        
                        auth.startActivityForSignInWithProvider(activity, provider.build())
                            .addOnSuccessListener { onLoginSuccess() }
                            .addOnFailureListener { errorMessage = "Google bejelentkezés sikertelen: ${it.localizedMessage}" }
                    } catch (e: Exception) {
                        errorMessage = "Hiba: ${e.localizedMessage}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color.DarkGray)
        ) {
            Icon(Icons.Default.AccountCircle, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(12.dp))
            Text("Bejelentkezés Google-fiókkal", color = Color.White)
        }

        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onNavigateToRegister) {
            Text("Nincs fiókod? Regisztráció (Vault jelszóerősség)", color = Color(0xFF6B4EE6))
        }

        if (errorMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(errorMessage, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun VaultRegistrationScreen(onBack: () -> Unit, onRegisterSuccess: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    val auth = FirebaseAuth.getInstance()
    val view = LocalView.current

    var entropy by remember { mutableStateOf(0.0) }
    LaunchedEffect(password) {
        if (password.isEmpty()) {
            entropy = 0.0
        } else {
            var pool = 0
            if (password.any { it.isLowerCase() }) pool += 26
            if (password.any { it.isUpperCase() }) pool += 26
            if (password.any { it.isDigit() }) pool += 10
            if (password.any { !it.isLetterOrDigit() }) pool += 32
            entropy = password.length * log2(pool.toDouble().coerceAtLeast(1.0))
        }
    }

    val tier = when {
        entropy < 1 -> 0  
        entropy < 30 -> 1 
        entropy < 50 -> 2 
        entropy < 70 -> 3 
        else -> 4         
    }
    
    // Hangeffektus lejátszása szintlépéskor
    LaunchedEffect(tier) {
        if (entropy > 0) {
            view.playSoundEffect(SoundEffectConstants.CLICK)
            if (tier == 4) {
                delay(150) // Dupla kattanás a legerősebb szintnél
                view.playSoundEffect(SoundEffectConstants.CLICK)
            }
        }
    }

    val tierName = when (tier) {
        0 -> "No lock at all"
        1 -> "A bent paperclip"
        2 -> "A padlock"
        3 -> "A deadbolt"
        else -> "A bank vault"
    }
    
    val tierDesc = when {
        entropy < 1 -> "The door is standing open."
        entropy < 20 -> "Cracked instantly."
        entropy < 30 -> "Cracked in under a second."
        entropy < 40 -> "Cracked in minutes."
        entropy < 50 -> "Cracked in days."
        entropy < 60 -> "Cracked in months."
        entropy < 70 -> "Cracked in years."
        else -> "Cracked in thousand years."
    }

    val tierColor = when (tier) {
        0 -> Color(0xFF555555) 
        1 -> Color(0xFFE57373) 
        2 -> Color(0xFFFFB74D) 
        3 -> Color(0xFFFFD54F) 
        else -> Color(0xFF4DB6AC) 
    }

    // A referenciavideóban minden sáv a SAJÁT (fix) színét kapja meg, amikor
    // aktívvá válik - nem mind az aktuális szint színét veszi fel.
    val segmentColors = listOf(Color(0xFFE57373), Color(0xFFFFB74D), Color(0xFFFFD54F), Color(0xFF4DB6AC))

    val animatedColor by animateColorAsState(targetValue = tierColor, animationSpec = tween(500), label = "tierColor")

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0F101A)).padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Vissza", tint = Color.Gray) }
        }
        
        Text("VAULT REGISTRATION", color = animatedColor, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email", color = Color.Gray) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF6B4EE6), unfocusedBorderColor = Color.DarkGray
            ),
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Password", color = Color.Gray, fontSize = 13.sp)
            TextButton(onClick = {
                password = generateStrongPassword()
                passwordVisible = true
            }) {
                Text("Suggest strong", color = animatedColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            placeholder = { Text("Start typing…", color = Color.DarkGray) },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff, tint = Color.Gray, contentDescription = null)
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = animatedColor, unfocusedBorderColor = Color.DarkGray
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Box(
            modifier = Modifier.fillMaxWidth().border(1.dp, animatedColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).background(Color(0xFF1A1C29), RoundedCornerShape(12.dp)).padding(16.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(70.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF252836)),
                    contentAlignment = Alignment.Center
                ) {
                    Crossfade(targetState = tier, animationSpec = tween(350), label = "lockTierCrossfade") { t ->
                        when (t) {
                            0 -> AnimatedDoorIcon(entropy)
                            1 -> AnimatedPaperclipIcon(entropy)
                            2 -> AnimatedPadlockIcon(entropy)
                            3 -> AnimatedDeadboltIcon(entropy)
                            else -> BankVaultIcon(entropy)
                        }
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        repeat(4) { index ->
                            val isActive = tier >= (index + 1)
                            val barColor by animateColorAsState(if (isActive) segmentColors[index] else Color(0xFF252836), tween(400), label = "barColor")
                            Box(modifier = Modifier.weight(1f).height(4.dp).padding(horizontal = 2.dp).clip(CircleShape).background(barColor))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(tierName, color = animatedColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(tierDesc, color = Color.LightGray, fontSize = 14.sp)
                    Text("${entropy.toInt()} bits of entropy", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                if (email.isNotEmpty() && password.isNotEmpty()) {
                    auth.createUserWithEmailAndPassword(email, password)
                        .addOnSuccessListener { onRegisterSuccess() }
                        .addOnFailureListener { errorMessage = it.localizedMessage ?: "Hiba történt" }
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (tier >= 3) Color(0xFF6B4EE6) else Color.DarkGray)
        ) {
            Text("Fiók létrehozása", fontSize = 16.sp)
        }

        if (errorMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(errorMessage, color = Color.Red, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun AnimatedDoorIcon(entropy: Double) {
    val rotation by animateFloatAsState(
        targetValue = (50f - (entropy * 25).toFloat()).coerceIn(0f, 50f),
        animationSpec = tween(300),
        label = "doorRotation"
    )
    Canvas(modifier = Modifier.size(44.dp)) {
        val w = size.width
        val h = size.height
        val strokeWidth = 3.dp.toPx()
        
        drawRect(
            color = Color.Gray,
            topLeft = Offset(w * 0.2f, h * 0.1f),
            size = Size(w * 0.6f, h * 0.8f),
            style = Stroke(width = strokeWidth)
        )
        
        rotate(rotation, pivot = Offset(w * 0.2f, h * 0.5f)) {
            drawRect(
                color = Color(0xFF8D6E63),
                topLeft = Offset(w * 0.2f, h * 0.1f),
                size = Size(w * 0.6f, h * 0.8f)
            )
            drawCircle(
                color = Color.Yellow,
                radius = 2.5.dp.toPx(),
                center = Offset(w * 0.7f, h * 0.5f)
            )
        }
    }
}

@Composable
fun AnimatedPaperclipIcon(entropy: Double) {
    val twistAngle by animateFloatAsState(
        targetValue = (entropy * 8).toFloat(),
        animationSpec = tween(400),
        label = "clipTwist"
    )
    Canvas(modifier = Modifier.size(44.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 3.dp.toPx()
        rotate(twistAngle, pivot = Offset(w / 2, h / 2)) {
            val path = Path().apply {
                moveTo(w * 0.35f, h * 0.3f)
                lineTo(w * 0.35f, h * 0.7f)
                quadraticBezierTo(w * 0.35f, h * 0.85f, w * 0.5f, h * 0.85f)
                quadraticBezierTo(w * 0.65f, h * 0.85f, w * 0.65f, h * 0.7f)
                lineTo(w * 0.65f, h * 0.25f)
                quadraticBezierTo(w * 0.65f, h * 0.15f, w * 0.5f, h * 0.15f)
                quadraticBezierTo(w * 0.35f, h * 0.15f, w * 0.35f, h * 0.25f)
            }
            drawPath(path, color = Color(0xFFE57373), style = Stroke(width = stroke, cap = StrokeCap.Round))
        }
    }
}

@Composable
fun AnimatedPadlockIcon(entropy: Double) {
    val shackleTarget = if (entropy > 35) 0.dp else (-6).dp
    val shackleOffsetDp by animateDpAsState(
        targetValue = shackleTarget,
        animationSpec = tween(300),
        label = "shackle"
    )
    Canvas(modifier = Modifier.size(44.dp)) {
        val w = size.width
        val h = size.height
        val shackleOffset = shackleOffsetDp.toPx()
        
        val path = Path().apply {
            moveTo(w * 0.3f, h * 0.45f + shackleOffset)
            lineTo(w * 0.3f, h * 0.3f + shackleOffset)
            quadraticBezierTo(w * 0.3f, h * 0.12f + shackleOffset, w * 0.5f, h * 0.12f + shackleOffset)
            quadraticBezierTo(w * 0.7f, h * 0.12f + shackleOffset, w * 0.7f, h * 0.3f + shackleOffset)
            lineTo(w * 0.7f, h * 0.45f + shackleOffset)
        }
        drawPath(path, color = Color.LightGray, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        drawRoundRect(
            color = Color(0xFFFFB74D),
            topLeft = Offset(w * 0.2f, h * 0.45f),
            size = Size(w * 0.6f, h * 0.45f),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        )
        drawCircle(color = Color.Black, radius = 2.5.dp.toPx(), center = Offset(w * 0.5f, h * 0.65f))
    }
}

@Composable
fun AnimatedDeadboltIcon(entropy: Double) {
    val slideTargetDp = (((entropy - 50) / 20) * 10).toFloat().coerceIn(0f, 12f).dp
    val slideOffsetDp by animateDpAsState(
        targetValue = slideTargetDp,
        animationSpec = tween(300),
        label = "deadbolt"
    )
    Canvas(modifier = Modifier.size(44.dp)) {
        val w = size.width
        val h = size.height
        val slideOffset = slideOffsetDp.toPx()
        
        drawRoundRect(
            color = Color(0xFFFFD54F),
            topLeft = Offset(w * 0.1f, h * 0.2f),
            size = Size(w * 0.55f, h * 0.6f),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        )
        drawRoundRect(
            color = Color.DarkGray,
            topLeft = Offset(w * 0.75f, h * 0.1f),
            size = Size(w * 0.2f, h * 0.8f),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        )
        drawRect(
            color = Color.LightGray,
            topLeft = Offset(w * 0.2f + slideOffset, h * 0.4f),
            size = Size(w * 0.5f, h * 0.2f)
        )
    }
}

@Composable
fun BankVaultIcon(entropy: Double) {
    val rotationAngle by animateFloatAsState(
        targetValue = (entropy * 15).toFloat(),
        animationSpec = tween(500),
        label = "vaultRotation"
    )
    Canvas(modifier = Modifier.size(44.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.width / 2.2f
        drawCircle(color = Color(0xFF263238), radius = radius, center = center)
        drawCircle(color = Color(0xFF4DB6AC), radius = radius * 0.85f, center = center, style = Stroke(width = 3.dp.toPx()))
        drawCircle(color = Color(0xFF004D40), radius = radius * 0.25f, center = center)
        
        rotate(rotationAngle, center) {
            for (i in 0 until 6) {
                val angle = i * (Math.PI / 3).toFloat()
                drawLine(
                    color = Color(0xFF80CBC4),
                    start = center,
                    end = Offset(center.x + radius * 0.75f * cos(angle), center.y + radius * 0.75f * sin(angle)),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
fun HomeScreen(exoPlayer: ExoPlayer?) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Üdvözöllek a Hangfolyam-ban!", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Keresd meg a zenét, vagy ismerd fel a beépített AI-val!", color = Color.Gray)
    }
}

data class Song(val title: String, val artist: String, val audioUrl: String)

// Ismert publikus Piped-tükrök. Ezek időnként leállnak/cserélődnek, ezért
// egy helyen tartjuk mindet, és sorban végigpróbáljuk őket lejátszáskor.
private val PIPED_INSTANCES = listOf(
    "pipedapi.kavin.rocks",
    "api.piped.projectsegfau.lt",
    "pipedapi.smnz.de",
    "piped-api.lunar.icu",
    "pipedapi.adminforge.de",
    "piped-api.privacydev.net"
)

private val INVIDIOUS_INSTANCES = listOf(
    "invidious.nerdvpn.de",
    "invidious.jing.rocks",
    "inv.tux.pizza",
    "yewtu.be"
)

// Közös segédfüggvény: egy YouTube videoId-ból megkeresi a lejátszható audio
// stream URL-t. Végigpróbálja az összes ismert Piped-tükröt, majd ha egyik
// sem válaszol, az Invidious-tükröket is - így ugyanaz a logika fut le
// mindenhol, ahol zenét kell lejátszani (kereső, hangfelismerő).
suspend fun resolveYoutubeStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
    for (instance in PIPED_INSTANCES) {
        try {
            val req = Request.Builder().url("https://$instance/streams/$videoId").build()
            val res = sharedHttpClient.newCall(req).execute()
            if (res.isSuccessful) {
                val json = JSONObject(res.body?.string() ?: "")
                val audioStreams = json.optJSONArray("audioStreams")
                if (audioStreams != null && audioStreams.length() > 0) {
                    val url = audioStreams.getJSONObject(0).optString("url")
                    if (url.isNotEmpty()) return@withContext url
                }
            }
        } catch (e: Exception) { /* megyünk a következő tükörre */ }
    }

    for (instance in INVIDIOUS_INSTANCES) {
        try {
            val req = Request.Builder().url("https://$instance/api/v1/videos/$videoId").build()
            val res = sharedHttpClient.newCall(req).execute()
            if (res.isSuccessful) {
                val json = JSONObject(res.body?.string() ?: "")
                val formats = json.optJSONArray("adaptiveFormats")
                if (formats != null) {
                    for (i in 0 until formats.length()) {
                        val format = formats.getJSONObject(i)
                        if (format.optString("type").startsWith("audio")) {
                            val url = format.optString("url")
                            if (url.isNotEmpty()) return@withContext url
                        }
                    }
                }
            }
        } catch (e: Exception) { /* megyünk a következő tükörre */ }
    }
    null
}

// Közös segédfüggvény: megkeresi egy dalt cím+előadó alapján a YouTube-on
// (előbb közvetlen kereséssel, ha az üres eredményt ad, Piped kereséssel).
suspend fun searchYouTubeCombined(query: String): List<Song> {
    val direct = searchYouTubeDirectly(query)
    return direct.ifEmpty { searchYouTubePiped(query) }
}

// Kis "equalizer" animáció - pulzáló sávok, amik lejátszás közben "táncolnak".
@Composable
fun EqualizerBars(playing: Boolean, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer")
    Row(
        modifier = modifier.height(22.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(3) { index ->
            val heightFraction by infiniteTransition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(350 + index * 120, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "eqBar$index"
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight(if (playing) heightFraction else 0.15f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
fun SearchScreen(exoPlayer: ExoPlayer?) {
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf(listOf<Song>()) }
    var isSearching by remember { mutableStateOf(false) }
    var activeSongIndex by remember { mutableStateOf<Int?>(null) }
    var currentLyrics by remember { mutableStateOf<String?>(null) }
    var aiStatus by remember { mutableStateOf<String?>(null) }
    
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0f) }
    var duration by remember { mutableStateOf(1f) }

    val coroutineScope = rememberCoroutineScope()

    fun triggerSearch() {
        if (query.isNotBlank()) {
            isSearching = true
            aiStatus = "Keresés a YouTube-on..."
            coroutineScope.launch {
                val optimizedQuery = optimizeSearchWithGemini(query)
                searchResults = searchYouTubeDirectly(optimizedQuery)
                if (searchResults.isEmpty()) searchResults = searchYouTubePiped(optimizedQuery)
                aiStatus = if (searchResults.isEmpty()) "Nincs találat." else null
                isSearching = false
            }
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            if (exoPlayer != null && exoPlayer.isPlaying) {
                currentPosition = exoPlayer.currentPosition.toFloat()
                duration = exoPlayer.duration.coerceAtLeast(1L).toFloat()
                isPlaying = true
            } else {
                isPlaying = false
            }
            delay(500)
        }
    }

    fun playSongAndFetchLyrics(index: Int) {
        val song = searchResults[index]
        activeSongIndex = index
        currentLyrics = "Kapcsolódás a zenei szerverekhez..."
        exoPlayer?.stop()
        
        coroutineScope.launch {
            val playUrl = resolveYoutubeStreamUrl(song.audioUrl) ?: ""

            if (playUrl.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    exoPlayer?.setMediaItem(MediaItem.fromUri(playUrl))
                    exoPlayer?.prepare()
                    exoPlayer?.play()
                    currentLyrics = "Dalszöveg betöltése..."
                }
            } else {
                currentLyrics = "Hiba: A zenei stream szerverek jelenleg nem elérhetőek. Kérlek próbáld újra később."
            }

            val lyrics = fetchLyrics(song.artist, song.title)
            if (!currentLyrics!!.startsWith("Hiba")) {
                currentLyrics = lyrics ?: "Nincs elérhető dalszöveg ehhez a dalhoz."
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Keresés (pl. Azahriah - 3 korty)...") },
            trailingIcon = {
                IconButton(onClick = { triggerSearch() }) {
                    Icon(Icons.Default.Search, contentDescription = "Keresés indítása")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { triggerSearch() }),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        if (aiStatus != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(aiStatus!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
        
        Spacer(modifier = Modifier.height(12.dp))

        AnimatedVisibility(
            visible = activeSongIndex != null && searchResults.isNotEmpty(),
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
        ) {
            val song = searchResults.getOrNull(activeSongIndex ?: -1)
            if (song != null) {
            Card(
                modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val discTransition = rememberInfiniteTransition(label = "disc")
                        val discAngle by discTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(animation = tween(3000, easing = LinearEasing)),
                            label = "discAngle"
                        )
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp).rotateModifier(if (isPlaying) discAngle else 0f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Most szól: ${song.title}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                            Text(song.artist, fontSize = 12.sp, color = Color.Gray, maxLines = 1)
                        }
                        EqualizerBars(playing = isPlaying)
                    }
                    Spacer(modifier = Modifier.height(4.dp))

                    Slider(
                        value = currentPosition,
                        onValueChange = { newVal ->
                            currentPosition = newVal
                            exoPlayer?.seekTo(newVal.toLong())
                        },
                        valueRange = 0f..duration,
                        modifier = Modifier.fillMaxWidth().height(20.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { if (exoPlayer?.isPlaying == true) exoPlayer.pause() else exoPlayer?.play() }) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Lejátszás/Szünet", modifier = Modifier.size(36.dp))
                        }
                        Spacer(modifier = Modifier.width(24.dp))
                        IconButton(onClick = { playSongAndFetchLyrics((activeSongIndex!! + 1) % searchResults.size) }) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Következő", modifier = Modifier.size(36.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(modifier = Modifier.height(80.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Text(currentLyrics ?: "Dalszöveg betöltése...", fontSize = 12.sp, color = Color.LightGray)
                    }
                }
            }
            }
        }

        if (isSearching) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(searchResults.indices.toList()) { index ->
                    val song = searchResults[index]
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { playSongAndFetchLyrics(index) }
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(song.title, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(song.artist, color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

suspend fun searchYouTubeDirectly(query: String): List<Song> = withContext(Dispatchers.IO) {
    try {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder().url("https://www.youtube.com/results?search_query=$encodedQuery").header("User-Agent", "Mozilla/5.0").build()
        val response = sharedHttpClient.newCall(request).execute()
        val html = response.body?.string() ?: return@withContext emptyList()
        val matcher = Pattern.compile("var ytInitialData = (\\{.*?\\});</script>").matcher(html)
        if (matcher.find()) {
            val contents = JSONObject(matcher.group(1)).optJSONObject("contents")?.optJSONObject("twoColumnSearchResultsRenderer")?.optJSONObject("primaryContents")?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")?.optJSONObject(0)?.optJSONObject("itemSectionRenderer")?.optJSONArray("contents")
            val list = mutableListOf<Song>()
            if (contents != null) {
                for (i in 0 until contents.length()) {
                    val videoRenderer = contents.optJSONObject(i)?.optJSONObject("videoRenderer")
                    if (videoRenderer != null) {
                        val videoId = videoRenderer.optString("videoId")
                        val title = videoRenderer.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: "Ismeretlen"
                        val owner = videoRenderer.optJSONObject("ownerText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: "Ismeretlen"
                        if (videoId.isNotEmpty()) list.add(Song(title, owner, videoId))
                    }
                }
            }
            return@withContext list
        }
    } catch (e: Exception) {}
    return@withContext emptyList()
}

suspend fun searchYouTubePiped(query: String): List<Song> = withContext(Dispatchers.IO) {
    try {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder().url("https://pipedapi.kavin.rocks/search?q=$encodedQuery").build()
        val response = sharedHttpClient.newCall(request).execute()
        if (response.isSuccessful) {
            val jsonArray = JSONObject(response.body?.string() ?: "").optJSONArray("items")
            val list = mutableListOf<Song>()
            if (jsonArray != null) {
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    if (item.optString("type") == "stream") {
                        list.add(Song(item.getString("title"), item.optString("uploaderName", "Ismeretlen"), item.getString("url").replace("/watch?v=", "")))
                    }
                }
                return@withContext list
            }
        }
    } catch (e: Exception) {}
    return@withContext emptyList()
}

suspend fun optimizeSearchWithGemini(userQuery: String): String = withContext(Dispatchers.IO) {
    try {
        val jsonBody = JSONObject().apply {
            put("contents", org.json.JSONArray().put(JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", "Készíts ebből tiszta YouTube keresőkifejezést (csak előadó és cím): '$userQuery'")))))
        }
        val request = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$GEMINI_API_KEY").post(jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())).build()
        val response = sharedHttpClient.newCall(request).execute()
        if (response.isSuccessful) {
            val candidates = JSONObject(response.body?.string() ?: "").optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                return@withContext candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")?.getJSONObject(0)?.optString("text")?.trim() ?: userQuery
            }
        }
    } catch (e: Exception) {}
    return@withContext userQuery
}

suspend fun fetchLyrics(artist: String, title: String): String? = withContext(Dispatchers.IO) {
    try {
        val cleanArtist = java.net.URLEncoder.encode(artist.take(20), "UTF-8")
        val cleanTitle = java.net.URLEncoder.encode(title.take(30).replace(Regex("\\(.*\\)"), "").trim(), "UTF-8")
        val request = Request.Builder().url("https://api.lyrics.ovh/v1/$cleanArtist/$cleanTitle").header("User-Agent", "Mozilla/5.0").build()
        val response = sharedHttpClient.newCall(request).execute()
        if (response.isSuccessful) return@withContext JSONObject(response.body?.string() ?: "").optString("lyrics")
    } catch (_: Exception) {}
    return@withContext null
}

@Composable
fun AudioRecognizerScreen(exoPlayer: ExoPlayer?) {
    val context = LocalContext.current
    var isListening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<RecognitionResult?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!it) status = "Mikrofon engedély megtagadva!"
    }

    fun startListening() {
        val permCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        if (permCheck != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (isListening) return

        isListening = true
        result = null
        status = "Zene rögzítése (6 másodperc)..."

        coroutineScope.launch {
            val recorder = AudioRecorder(context)
            var audioFile: File? = null
            try {
                val recordedFile = recorder.start()
                audioFile = recordedFile
                delay(6000)
                recorder.stop()

                status = "Felismerés folyamatban..."
                // A ténylegesen működő AudD-alapú felismerő (a projektben már ott
                // volt az API-kulcs, csak eddig sehol nem hívtuk meg).
                val recognized = RecognitionApi.recognize(recordedFile)

                if (recognized != null) {
                    result = recognized
                    status = "Megvan: ${recognized.artist} - ${recognized.title}. Keresés és lejátszás..."

                    val songs = searchYouTubeCombined("${recognized.artist} ${recognized.title}")
                    if (songs.isNotEmpty()) {
                        val playUrl = resolveYoutubeStreamUrl(songs[0].audioUrl)
                        if (playUrl != null) {
                            withContext(Dispatchers.Main) {
                                exoPlayer?.setMediaItem(MediaItem.fromUri(playUrl))
                                exoPlayer?.prepare()
                                exoPlayer?.play()
                            }
                            status = "Lejátszás: ${recognized.artist} - ${recognized.title}"
                        } else {
                            status = "Találat megvan, de a stream szerverek most elfoglaltak. Próbáld újra."
                        }
                    } else {
                        status = "Nem található lejátszható verzió a YouTube-on."
                    }
                } else {
                    status = "Nem sikerült felismerni. Próbáld hangosabban, közelebb a hangforráshoz!"
                }
            } catch (e: Exception) {
                status = "Hiba történt: ${e.message}"
                try { recorder.stop() } catch (_: Exception) {}
            } finally {
                isListening = false
                audioFile?.let { if (it.exists()) it.delete() }
            }
        }
    }

    ModernRecognitionScreen(
        isListening = isListening,
        result = result,
        onStartListening = { startListening() },
        status = status
    )
}

@Composable
fun ProfileScreen(onSignOut: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button(onClick = onSignOut) { Text("Kijelentkezés") }
    }
}
