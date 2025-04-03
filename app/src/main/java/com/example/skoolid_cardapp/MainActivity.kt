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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
            SmartCardAuthScreen(viewModel)
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
    viewModel: SmartCardAuthViewModel
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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

class SmartCardAuthViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<AuthState>(AuthState.Idle)
    val uiState: StateFlow<AuthState> = _uiState.asStateFlow()

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

            _uiState.value = AuthState.Success
        } catch (e: Exception) {
            _uiState.value = AuthState.Error(e.message ?: "Unknown error")
        } finally {
            isoDep?.close()
        }
    }

    fun resetState() {
        _uiState.value = AuthState.Idle
    }
}

sealed class AuthState {
    object Idle : AuthState()
    object Communicating : AuthState()
    object Success : AuthState()
    data class Error(val message: String) : AuthState()
}