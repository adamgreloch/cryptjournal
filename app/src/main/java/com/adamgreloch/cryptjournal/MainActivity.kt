package com.adamgreloch.cryptjournal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Log.println
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.FragmentActivity
import com.adamgreloch.cryptjournal.ui.theme.CryptjournalTheme
import java.io.*
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
                                onOpenBufferPress = { text -> openEntry(text) },
                                onSaveBufferPress = { text -> saveEntry(text) }
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

    private fun askJournalDocumentTreePermission() {
        startOpenDocumentTreeActivity.launch(null)
    }

    private val startOpenDocumentTreeActivity =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
                uri ->
            if (uri != null) {
                setJournalPath(uri)

                val contentResolver = applicationContext.contentResolver
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                // Check for the freshest data.
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            }
        }

    private fun arePermissionsGranted(uriString: String): Boolean {
        // list of all persisted permissions for our app
        val list = contentResolver.persistedUriPermissions
        for (i in list.indices) {
            val persistedUriString = list[i].uri.toString()

            if (persistedUriString == uriString && list[i].isWritePermission && list[i].isReadPermission) {
                return true
            }
        }
        return false
    }

    private val currentTime = LocalDateTime.now()
    private val fileNameFormat = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val todayFileName = currentTime.format(fileNameFormat) + ".txt.pgp"

    fun openEntry(text: MutableState<String>) {
        val sharedPref = this.getPreferences(Context.MODE_PRIVATE) ?: return
        val lastFileName = sharedPref.getString(getString(R.string.LastFileNamePref), "") ?: ""
        var journalPath = sharedPref.getString(getString(R.string.JournalPathPref), "") ?: ""

        println(Log.INFO, null, journalPath)

        val newPost = lastFileName != todayFileName

        when {
            journalPath == "" -> {
                // Installation is fresh. Ask user for permission in specified journal directory.
                Log.w(null, "journal URI not found")
                Toast.makeText(this, "Specify journal directory and try again.", Toast.LENGTH_LONG)
                    .show()
                askJournalDocumentTreePermission()
            }
            arePermissionsGranted(journalPath) -> {
                with (sharedPref.edit()) {
                    putString(getString(R.string.CurrentFileNamePref), todayFileName)
                    apply()
                }

                if (newPost) {
                    createNewFile(Uri.parse(journalPath), todayFileName)
                    text.value = createNewEntryString()
                }
                else {
                    val currentEntryUri = sharedPref.getString(getString(R.string.CurrentEntryUri), "") ?: ""
                    // Open entry from today and append current time.
                    text.value = readFile(Uri.parse(currentEntryUri))
                }
            }
            else -> Log.w(null, "permissions for specified URI not granted")
        }

        println(Log.INFO, null, "open")
    }

    private fun setJournalPath(uri: Uri?) {
        val sharedPref = this.getPreferences(Context.MODE_PRIVATE) ?: return

        with (sharedPref.edit()) {
            putString(getString(R.string.JournalPathPref), uri.toString())
            apply()
        }

        println(Log.INFO, null, "Set journal_path to ${uri.toString()}")
    }

    private fun saveEntry(text: MutableState<String>) {
        val sharedPref = this.getPreferences(Context.MODE_PRIVATE) ?: return
        val currentEntryUri = sharedPref.getString(getString(R.string.CurrentEntryUri), "") ?: ""

        with (sharedPref.edit()) {
            putString(getString(R.string.LastFileNamePref), todayFileName)
            apply()
        }

        writeFile(text.value, Uri.parse(currentEntryUri))
    }

    private fun readFile(uri: Uri): String {
        val stringBuilder = StringBuilder()
        contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    stringBuilder.append(line)
                    line = reader.readLine()
                }
            }
        }
        return stringBuilder.toString()
    }

    private fun writeFile(content: String, uri: Uri) {
        try {
            contentResolver.openFileDescriptor(uri, "w")?.use {
                FileOutputStream(it.fileDescriptor).use {
                    it.write(content.toByteArray())
                }
            }
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun createNewFile(dirUri: Uri, fileName: String) {
        val dir = DocumentFile.fromTreeUri(this, dirUri)
            ?: throw IOException("Could not specified find directory")
        val file = dir.createFile("application/pgp-encrypted", fileName)

        val sharedPref = this.getPreferences(Context.MODE_PRIVATE) ?: return
        with (sharedPref.edit()) {
            putString(getString(R.string.CurrentEntryUri), file?.uri.toString())
            apply()
        }
    }

    private fun createNewEntryString() : String {
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

}

private fun specifyJournalPath(): String {
    return ""
}

private fun importSecretKey() {
}

@Preview(
    showBackground = true,
    showSystemUi = true)
@Composable
private fun DefaultPreview(modifier: Modifier = Modifier) {
    CryptjournalTheme {
        JournalView({}, {})
    }
}

@Composable
private fun JournalView(onOpenBufferPress: (MutableState<String>) -> Unit,
                        onSaveBufferPress: (MutableState<String>) -> Unit) {
    val text = rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = { AppBar(onOpenBufferPress, onSaveBufferPress, text) },
        content = { paddingValues ->
            EditorField(text = text.value, onTextChange = { text.value = it }, paddingValues = paddingValues)
        })
}

@Composable
private fun AppBar(onOpenBufferPress: (MutableState<String>) -> Unit,
                   onSaveBufferPress: (MutableState<String>) -> Unit,
                   text: MutableState<String>) {
    var expanded by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = {
            IconButton(onClick = { onOpenBufferPress(text) }) {
                Icon(Icons.Filled.Add, contentDescription = "Open journal buffer")
            }
            IconButton(onClick = { onSaveBufferPress(text) }) {
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

@Composable
private fun EditorField(text: String, onTextChange: (String) -> Unit, paddingValues: PaddingValues) {

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
            .padding(paddingValues)
    )
}
