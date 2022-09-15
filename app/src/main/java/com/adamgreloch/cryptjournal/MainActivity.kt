package com.adamgreloch.cryptjournal

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.util.Log
import android.util.Log.println
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.adamgreloch.cryptjournal.ui.theme.CryptjournalTheme
import java.util.concurrent.Executor

private lateinit var executor: Executor
private lateinit var biometricPrompt: BiometricPrompt
private lateinit var promptInfo: BiometricPrompt.PromptInfo

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        executor = ContextCompat.getMainExecutor(this)

        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int,
                                                   errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(applicationContext,
                        "Authentication error: $errString", Toast.LENGTH_SHORT)
                        .show()
                    finishAndRemoveTask()
                }

                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    Toast.makeText(applicationContext,
                        "Authentication succeeded!", Toast.LENGTH_SHORT)
                        .show()
                    setContent {
                        CryptjournalTheme {
                            JournalView()
                        }
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(applicationContext, "Authentication failed",
                        Toast.LENGTH_SHORT)
                        .show()
                    finishAndRemoveTask()
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Access restricted")
            .setSubtitle("Log in to Cryptjournal using your biometric credential")
            .setNegativeButtonText("Use account password")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}

@Preview(
    showBackground = true,
    showSystemUi = true)
@Composable
private fun DefaultPreview(modifier: Modifier = Modifier) {
    CryptjournalTheme {
        JournalView()
    }
}

@Composable
private fun JournalView() {
    var text by rememberSaveable { mutableStateOf("") }

    Scaffold(topBar = { AppBar(text) }) {
        EditorField(text = text, onTextChange = { text = it })
    }
}

@Composable
private fun AppBar(text: String) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = {
            IconButton(onClick = {
                openBuffer(context)
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Open journal buffer")
            }
            IconButton(onClick = {
                saveBuffer(text)
            }) {
                Icon(Icons.Filled.Save, contentDescription = "Save journal buffer")
            }
            IconButton(onClick = { expanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }) {
                DropdownMenuItem(onClick = { importSecretKey() }) {
                    Text("Import secret key")
                }
                DropdownMenuItem(onClick = { /* TODO */ }) {
                    Text("Key info")
                }
                Divider()
                DropdownMenuItem(onClick = { /* TODO */ }) {
                    Text("About")
                }
            }
        }
    )
}

fun Context.getActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.getActivity()
    else -> null
}

private fun importSecretKey() {
}

private fun openBuffer(context: Context) {
    createFile(context.getActivity() as FragmentActivity)

    println(Log.INFO, null, "open")
}

private fun saveBuffer(text: String) {
    println(Log.VERBOSE, null, encryptText(text, "", "", ""))
}

@Composable
private fun EditorField(text: String, onTextChange: (String) -> Unit) {

    TextField(
        value = text,
        onValueChange = onTextChange,
        textStyle = TextStyle(
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace
        ),
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
    )
}
