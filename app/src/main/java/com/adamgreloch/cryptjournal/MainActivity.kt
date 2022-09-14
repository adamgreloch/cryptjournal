package com.adamgreloch.cryptjournal

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.adamgreloch.cryptjournal.ui.theme.CryptjournalTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CryptjournalTheme {
                JournalView()
            }
        }
    }
}

@Preview(
    showBackground = true,
    showSystemUi = true)
@Composable
fun DefaultPreview(modifier: Modifier = Modifier) {
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
    TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = {
            IconButton(onClick = { openBuffer() }) {
                Icon(Icons.Filled.Add, contentDescription = "Open journal buffer")
            }
            IconButton(onClick = {
                saveBuffer(text)
            }) {
                Icon(Icons.Filled.Save, contentDescription = "Save journal buffer")
            }
            IconButton(onClick = { /* TODO */ }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
        }
    )
}

private fun openBuffer() {
    println("open")
}

private fun saveBuffer(text: String) {
    println(text)
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
