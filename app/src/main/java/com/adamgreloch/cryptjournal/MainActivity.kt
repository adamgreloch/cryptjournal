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
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.adamgreloch.cryptjournal.ui.theme.CryptjournalTheme
import java.io.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executor

private lateinit var executor: Executor
private lateinit var biometricPrompt: BiometricPrompt
private lateinit var promptInfo: BiometricPrompt.PromptInfo
private lateinit var encryptionProvider: EncryptionProvider

class MainActivity : FragmentActivity() {
    private val currentTime = LocalDateTime.now()
    private val fileNameFormat = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val dateFormat = DateTimeFormatter.ofPattern("eeee, dd.MM.yy")
    private val timeFormat = DateTimeFormatter.ofPattern("hh:mm")
    private val todayFileName = currentTime.format(fileNameFormat) + ".txt.pgp"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        executor = ContextCompat.getMainExecutor(this)

        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence
                ) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(
                        applicationContext,
                        "Authentication error: $errString", Toast.LENGTH_SHORT
                    )
                        .show()
                    finishAndRemoveTask()
                }

                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    super.onAuthenticationSucceeded(result)
                    Toast.makeText(
                        applicationContext,
                        "Authentication succeeded!", Toast.LENGTH_SHORT
                    )
                        .show()

                    val masterKey = MasterKey.Builder(applicationContext).build()

                    val encryptedPrefs = EncryptedSharedPreferences.create(
                        applicationContext,
                        "gpg_storage",
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )

                    encryptionProvider = EncryptionProvider(encryptedPrefs)

                    setContent {
                        CryptjournalTheme {
                            JournalView(
                                openEntry = { text -> openEntry(text) },
                                saveEntry = { text -> saveEntry(text) },
                                specifyJournalPath = { askJournalDocumentTreePermission() },
                                showKeyInfo = { showKeyInfo() },
                                reconfigurePGP = { reconfigurePGP() }
                            )
                        }
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(
                        applicationContext, "Authentication failed",
                        Toast.LENGTH_SHORT
                    )
                        .show()
                    finishAndRemoveTask()
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_prompt_title))
            .setSubtitle(getString(R.string.biometric_prompt_subtitle))
            .setNegativeButtonText(getString(R.string.biometric_prompt_negative_button_text))
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun askJournalDocumentTreePermission() {
        startOpenDocumentTreeActivity.launch(null)
    }

    private val startOpenDocumentTreeActivity =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
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

    private val newEntryString = with(StringBuilder()) {
        append(currentTime.format(dateFormat))
        append("\n\n")
        append(currentTime.format(timeFormat))
        append("\n\n")
        toString()
    }

    fun openEntry(text: MutableState<String>) {
        val sharedPref = this.getPreferences(Context.MODE_PRIVATE) ?: return
        val lastFileName = sharedPref.getString(getString(R.string.last_file_name_pref), "") ?: ""
        val journalPath = sharedPref.getString(getString(R.string.journal_path_pref), "") ?: ""

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
                with(sharedPref.edit()) {
                    putString(getString(R.string.current_file_name_pref), todayFileName)
                    apply()
                }

                if (newPost) {
                    createNewFile(Uri.parse(journalPath), todayFileName)
                    text.value = newEntryString
                } else {
                    // Read existing entry from today and append current time.
                    val currentEntryUri =
                        sharedPref.getString(getString(R.string.current_entry_uri_pref), "") ?: ""
                    val sb = StringBuilder(readFile(Uri.parse(currentEntryUri)))

                    sb.appendLine().append(currentTime.format(timeFormat)).appendLine()
                    text.value = sb.toString()
                }
            }
            else -> {
                Log.w(null, "permissions for specified URI not granted")
                Toast.makeText(
                    this,
                    "Could not get necessary permissions for given URI.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setJournalPath(uri: Uri?) {
        val sharedPref = this.getPreferences(Context.MODE_PRIVATE) ?: return

        with(sharedPref.edit()) {
            putString(getString(R.string.journal_path_pref), uri.toString())
            apply()
        }

        println(Log.INFO, null, "Set journal_path to ${uri.toString()}")
    }

    private fun saveEntry(text: MutableState<String>) {
        val sharedPref = this.getPreferences(Context.MODE_PRIVATE) ?: return
        val currentEntryUri = sharedPref.getString(getString(R.string.current_entry_uri_pref), "") ?: ""

        with(sharedPref.edit()) {
            putString(getString(R.string.last_file_name_pref), todayFileName)
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
                    stringBuilder.append(line).appendLine()
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
        with(sharedPref.edit()) {
            putString(getString(R.string.current_entry_uri_pref), file?.uri.toString())
            apply()
        }
    }

    private fun showKeyInfo() {
    }

    private fun reconfigurePGP() {
        // Must ask for user confirmation before proceeding further
    }
}

@Preview(
    showBackground = true,
    showSystemUi = true
)
@Composable
private fun DefaultPreview(modifier: Modifier = Modifier) {
    CryptjournalTheme {
        JournalView({}, {}, {}, {}, {})
    }
}

@Composable
private fun JournalView(
    openEntry: (MutableState<String>) -> Unit,
    saveEntry: (MutableState<String>) -> Unit,
    specifyJournalPath: () -> Unit,
    showKeyInfo: () -> Unit,
    reconfigurePGP: () -> Unit
) {
    val text = rememberSaveable { mutableStateOf("") }

    var aboutOpen by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { openEntry(text) }) {
                        Icon(Icons.Filled.Add, contentDescription = "Open journal buffer")
                    }
                    IconButton(onClick = { saveEntry(text) }) {
                        Icon(Icons.Filled.Save, contentDescription = "Save journal buffer")
                    }
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(onClick = { specifyJournalPath() }) {
                            Text("Specify journal path")
                        }
                        DropdownMenuItem(onClick = { reconfigurePGP() }) {
                            Text("Reconfigure PGP")
                        }
                        DropdownMenuItem(onClick = { showKeyInfo() }) {
                            Text("Key info")
                        }
                        Divider()
                        DropdownMenuItem(onClick = { aboutOpen = true }) {
                            Text("About")
                        }
                    }
                }
            )
        },
        content = { paddingValues ->
            if (aboutOpen)
                AboutDialog(onDismissRequest = { aboutOpen = false })

            EditorField(
                text = text.value,
                onTextChange = { text.value = it },
                paddingValues = paddingValues
            )
        })
}

@Composable
private fun EditorField(
    text: String,
    onTextChange: (String) -> Unit,
    paddingValues: PaddingValues
) {
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

@Composable
private fun AboutDialog(onDismissRequest: () -> Unit) {
    Dialog(onDismissRequest = onDismissRequest) {
        Column {
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 36.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.short_description),
                fontSize = 16.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.github_url),
                fontSize = 16.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}
