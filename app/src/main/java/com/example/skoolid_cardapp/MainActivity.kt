package com.example.skoolid_cardapp

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode.Companion.Color
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.Red
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var pendingIntent: PendingIntent
    private lateinit var viewModel: SmartCardAuthViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize ViewModel without @Composable context
        viewModel = ViewModelProvider(this)[SmartCardAuthViewModel::class.java]

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC is not available on this device", Toast.LENGTH_LONG).show()
            finish()  // Close the app if no NFC
            return
        }
        setContent {
            val viewModel: SmartCardAuthViewModel = viewModel
            SmartCardAuthScreen(
                viewModel,nfcAdapter != null
            )
        }

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG, Tag::class.java)
        tag?.let { viewModel.handleNfcIntent(it) }
    }


    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(
            this,
            pendingIntent,
            arrayOf(IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)),
            arrayOf(arrayOf(IsoDep::class.java.name))
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    private fun setupForegroundDispatch(adapter: NfcAdapter) {
        val intent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_MUTABLE
        )
        adapter.enableForegroundDispatch(
            this,
            pendingIntent,
            arrayOf(IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)),
            arrayOf(arrayOf(IsoDep::class.java.name))
        )
    }
}



@Composable
fun SmartCardAuthScreen(
    viewModel: SmartCardAuthViewModel,
    isNfcSupported: Boolean
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        if (!isNfcSupported) {
            Text("This device does not support NFC")
            return
        } else {
            when (state) {
                is AuthState.Idle -> {
                    Text("Tap your smart card")
                    CircularProgressIndicator()
                }

                is AuthState.Communicating -> {
                    Text("Communicating with card...")
                    LinearProgressIndicator()
                }

                is AuthState.Success -> {
                    Text("Authentication successful!")
                    Button(onClick = { /* Handle success */ }) {
                        Text("Continue")
                    }
                }

                is AuthState.Error -> {
                    Text("Error: ${(state as AuthState.Error).message}")
                    Button(onClick = { viewModel.resetState() }) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

class SmartCardAuthViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<AuthState>(AuthState.Idle)
    val uiState: StateFlow<AuthState> = _uiState.asStateFlow()

    var showMessage: (String) -> Unit = { }

    fun handleNfcIntent(tag: Tag) {  // Now accepts Tag directly
        viewModelScope.launch(Dispatchers.IO) {
            authenticateWithCard(IsoDep.get(tag))
        }
    }

    private suspend fun authenticateWithCard(isoDep: IsoDep?) {
        try {
            _uiState.value = AuthState.Communicating
            isoDep?.connect()

            // APDU communication logic here
            // ... (same as previous implementation)
            // 1. Select Applet
            val aid = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
            val selectResponse = isoDep?.transceive(buildSelectApdu(aid))
            if (!isSuccess(selectResponse)) throw Exception("Applet selection failed")

            // 2. Verify PIN
            val verifyResponse = isoDep?.transceive(
                buildCommandApdu(0x80, 0x20, 0x00, 0x00, byteArrayOf(0x31, 0x32, 0x33, 0x34))
            )
            if (!isSuccess(verifyResponse)) throw Exception("PIN verification failed")

            // 3. Get Challenge
            val challengeResponse = isoDep?.transceive(
                buildCommandApdu(0x80, 0x30, 0x00, 0x00, null)
            )
            val challenge = challengeResponse?.copyOfRange(0, challengeResponse.size - 2)

            // 4. Encrypt Challenge
            val keyBytes = ByteArray(16) { it.toByte() }
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
            val encryptedChallenge = cipher.doFinal(challenge)

            // 5. Authenticate
            val authResponse = isoDep?.transceive(
                buildCommandApdu(0x80, 0x32, 0x00, 0x00, encryptedChallenge)
            )
            if (!isSuccess(authResponse)) throw Exception("Authentication failed")

            _uiState.value = AuthState.Success
            showMessage("Authentication successful!")

            _uiState.value = AuthState.Success
        } catch (e: Exception) {
            _uiState.value = AuthState.Error(e.message ?: "Unknown error")
            showMessage("Authentication failed: ${e.message}")

        } finally {
            isoDep?.close()
        }
    }

    fun resetState() {
        _uiState.value = AuthState.Idle
    }

    private fun buildSelectApdu(aid: ByteArray): ByteArray {
        return byteArrayOf(
            0x00, 0xA4.toByte(), 0x04, 0x00,
            aid.size.toByte(), *aid, 0x00
        )
    }
    private fun buildCommandApdu(
        cla: Int,
        ins: Int,
        p1: Int,
        p2: Int,
        data: ByteArray?
    ): ByteArray {
        return ByteArrayOutputStream().apply {
            write(cla)
            write(ins)
            write(p1)
            write(p2)
            data?.let {
                write(it.size)
                write(it)
            } ?: write(0)
            write(0)
        }.toByteArray()
    }

    private fun isSuccess(response: ByteArray?): Boolean {
        return response != null &&
                response.size >= 2 &&
                response[response.size - 2] == 0x90.toByte() &&
                response[response.size - 1] == 0x00.toByte()
    }
}

sealed class AuthState {
    object Idle : AuthState()
    object Communicating : AuthState()
    object Success : AuthState()
    data class Error(val message: String) : AuthState()
}