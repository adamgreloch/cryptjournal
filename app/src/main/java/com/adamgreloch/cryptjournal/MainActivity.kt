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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import java.lang.NullPointerException
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

    private var keyFilePath = Uri.EMPTY

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

                    val masterKey = with(MasterKey.Builder(applicationContext)) {
                        setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        build()
                    }

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
                                reconfigurePGP = { reconfigurePGP() },
                                configurePGP = { password -> configurePGP(password) },
                                onPickKeyFilePress = { pickKeyFile() }
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
        pickJournalPathActivity.launch(null)
    }

    private val pickKeyFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            keyFilePath = uri }

    private val pickJournalPathActivity =
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

    /**
     * @return true, if PGP configuration necessary; false if
     */
    fun openEntry(text: MutableState<String>) : Boolean {
        val sharedPref = this.getPreferences(Context.MODE_PRIVATE)
            ?: throw NullPointerException("Could not access shared preferences")
        val lastFileName = sharedPref.getString(getString(R.string.last_file_name_pref), "") ?: ""
        val journalPath = sharedPref.getString(getString(R.string.journal_path_pref), "") ?: ""

        println(Log.INFO, null, journalPath)

        val newPost = lastFileName != todayFileName

        when {
            journalPath == "" -> {
                // Installation is fresh. Ask user for permission in specified journal directory and configure PGP.
                Log.w(null, "journal URI not found")
                Toast.makeText(this, "Specify journal directory and try again.", Toast.LENGTH_LONG)
                    .show()

                return true
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
                    val sb = StringBuilder(readEncryptedFile(Uri.parse(currentEntryUri)))

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

        return false
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

        text.value += "\n"

        writeEncryptedFile(text.value, Uri.parse(currentEntryUri))
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

    private fun readEncryptedFile(uri: Uri): String {
        if (!encryptionProvider.isConfigured()) {
            Toast.makeText(
                this,
                "Could not read a file. Encryption is not configured!",
                Toast.LENGTH_LONG
            ).show()
            return ""
        }

        return encryptionProvider.decryptText(readFile(uri))
    }

    private fun writeEncryptedFile(content: String, uri: Uri) {
        if (!encryptionProvider.isConfigured()) {
            Toast.makeText(
                this,
                "Could not write a file. Encryption is not configured!",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            contentResolver.openFileDescriptor(uri, "w")?.use {
                FileOutputStream(it.fileDescriptor).use {
                    it.write(encryptionProvider.encryptText(content).toByteArray())
                }
                Toast.makeText(
                    this,
                    "Entry encrypted and saved.",
                    Toast.LENGTH_LONG
                ).show()
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

    private fun pickKeyFile() {
        Toast.makeText(
            this,
            "Pick your PGP key file.",
            Toast.LENGTH_LONG
        ).show()

        pickKeyFile.launch(arrayOf("*/*"))
    }

    private fun configurePGP(keyPassword: String) : Boolean {
        if (keyFilePath == Uri.EMPTY || keyFilePath == null)
            throw IOException("Key file path not chosen")

        val asciiSecretKey = readFile(keyFilePath)

        if (encryptionProvider.importKey(asciiSecretKey, keyPassword))
            Toast.makeText(
                this,
                "Key imported successfully! (userId: ${encryptionProvider.getUserId()})",
                Toast.LENGTH_LONG
            ).show()
        else {
            Toast.makeText(this, "Key import failed!", Toast.LENGTH_LONG).show()
            return false
        }

        return true
    }
}

@Preview(
    showBackground = true,
    showSystemUi = true
)
@Composable
private fun DefaultPreview(modifier: Modifier = Modifier) {
    CryptjournalTheme {
        JournalView({false}, {}, {}, {}, {}, {false}, {})
    }
}

@Composable
private fun JournalView(
    openEntry: (MutableState<String>) -> Boolean,
    saveEntry: (MutableState<String>) -> Unit,
    specifyJournalPath: () -> Unit,
    showKeyInfo: () -> Unit,
    reconfigurePGP: () -> Unit,
    configurePGP: (String) -> Boolean,
    onPickKeyFilePress: () -> Unit
) {
    val text = rememberSaveable { mutableStateOf("") }
    val password = remember { mutableStateOf("") }

    var aboutOpen by remember { mutableStateOf(false) }
    var keyInfoOpen by remember { mutableStateOf(false) }
    var configurationOpen by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { configurationOpen = openEntry(text) }) {
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
                        DropdownMenuItem(onClick = {
                            // TODO: reconfigurePGP()
                            configurationOpen = true
                        }) {
                            Text("Reconfigure PGP")
                        }
                        DropdownMenuItem(onClick = { keyInfoOpen = true }) {
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

            if (configurationOpen)
                ConfigurationDialog(text = password.value,
                    specifyJournalPath = specifyJournalPath,
                    onTextChange = { password.value = it },
                    onPickKeyFilePress = onPickKeyFilePress,
                    onDismissRequest = { configurationOpen = false },
                    onConfirmPress = { configurationOpen = !configurePGP(password.value) }
                )

            if (keyInfoOpen)
                KeyInfoDialog(onDismissRequest = { keyInfoOpen = false })

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
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            color = Color.DarkGray,
            shape = RoundedCornerShape(size = 10.dp)
        ) {
            Column(Modifier.padding(all = 16.dp)) {
                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = 36.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(8.dp))
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
}


@Composable
private fun ConfigurationDialog(
    text: String,
    onTextChange: (String) -> Unit,
    specifyJournalPath: () -> Unit,
    onPickKeyFilePress: () -> Unit,
    onDismissRequest: () -> Unit,
    onConfirmPress: () -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            color = Color.DarkGray,
            shape = RoundedCornerShape(size = 10.dp)
        ) {
            Column(Modifier.padding(all = 8.dp)) {
                Text(
                    text = "Set your journal directory path.",
                    fontSize = 16.sp,
                )
                Button( onClick = specifyJournalPath ) {
                    Text("Specify path")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Pick your GPG secret key file.",
                    fontSize = 16.sp,
                )
                Spacer(Modifier.height(8.dp))
                Button( onClick = onPickKeyFilePress ) {
                    Text("Pick key file")
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Enter your PGP secret key password.",
                    fontSize = 16.sp,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    label = { Text("Enter password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.align(Alignment.End)) {
                    Button(onClick = onConfirmPress) {
                        Text("OK")
                    }
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = onDismissRequest) {
                        Text("Abort")
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyInfoDialog(onDismissRequest: () -> Unit) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            color = Color.DarkGray,
            shape = RoundedCornerShape(size = 10.dp)
        ) {
            Column(Modifier.padding(all = 8.dp)) {
                Text(
                    text = "Key info",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${encryptionProvider.getUserId()}\n"
                    + "${encryptionProvider.getAlgorithmName()} ${encryptionProvider.getKeyId()}",
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Key created on ${encryptionProvider.getKeyCreationDate()}",
                    fontSize = 16.sp
                )
            }
        }
    }
}
