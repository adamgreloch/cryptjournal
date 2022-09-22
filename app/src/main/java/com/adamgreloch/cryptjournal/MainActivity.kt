package com.adamgreloch.cryptjournal

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Log.println
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.app.ActivityCompat.startActivityForResult
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.adamgreloch.cryptjournal.ui.theme.CryptjournalTheme
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
                            JournalView(
                                onOpenBufferPress = { openDocument() }
                            )
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

    private val startOpenDocumentTreeActivity =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
                uri -> setJournalPath(uri) }

    fun openDocument() { startOpenDocumentTreeActivity.launch(null) }

    private fun setJournalPath(uri: Uri?) {
        val sharedPref = this.getPreferences(Context.MODE_PRIVATE) ?: return
        println(Log.INFO, null, "Set journal_path to ${uri.toString()}")
        with (sharedPref.edit()) {
            putString("journal_path", uri.toString())
            apply()
        }
    }

}

@Preview(
    showBackground = true,
    showSystemUi = true)
@Composable
private fun DefaultPreview(modifier: Modifier = Modifier) {
    CryptjournalTheme {
        JournalView {}
    }
}

@Composable
private fun JournalView(onOpenBufferPress: () -> Unit) {
    var text = rememberSaveable { mutableStateOf("") }

    Scaffold(topBar = { AppBar(onOpenBufferPress, text) }) {
        EditorField(text = text.value, onTextChange = { text.value = it })
    }
}

@Composable
private fun AppBar(onOpenBufferPress: () -> Unit, text: MutableState<String>) {
    var expanded by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = {
            IconButton(onClick = onOpenBufferPress) {
                Icon(Icons.Filled.Add, contentDescription = "Open journal buffer")
            }
            IconButton(onClick = {
                saveBuffer(text.value)
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
                DropdownMenuItem(onClick = { specifyJournalPath() }) {
                    Text("Specify journal path")
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

fun specifyJournalPath(): String {
    return ""
}

fun createNewEntryString() : String {
    val current = LocalDateTime.now()
    val dateFormat = DateTimeFormatter.ofPattern("eeee, dd.MM.yy")
    val timeFormat = DateTimeFormatter.ofPattern("hh:mm")

    val sb = StringBuilder()

    sb.append(current.format(dateFormat))
        .append("\n\n")
        .append(current.format(timeFormat))
        .append("\n\n")

    return sb.toString()
}

fun Context.getActivity(): Activity = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.getActivity()
    else -> throw ActivityNotFoundException()
}

private fun importSecretKey() {
}

private fun openBuffer(activity: MainActivity, text: MutableState<String>) {

    val sharedPref = activity.getPreferences(Context.MODE_PRIVATE) ?: return
    val lastFileName = sharedPref.getString("last_file_name", null)
    val journalPath = sharedPref.getString("journal_path", null)

    if (journalPath == null)
        // Installation is fresh. Ask user to specify journal path
        with (sharedPref.edit()) {
            putString("journal_path", specifyJournalPath())
            apply()
        }

    if (lastFileName == null) {
        text.value = createNewEntryString()
    }

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
