package uk.krodity.crremote.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** First-run pairing. Normally skipped entirely: tapping the broker's /pair page
 *  deep-links straight in with both fields filled. */
@Composable
fun PairScreen(
    initialBroker: String,
    initialToken: String,
    onPair: (String, String) -> Unit,
) {
    var broker by rememberSaveable { mutableStateOf(initialBroker.ifBlank { "aepc" }) }
    var token by rememberSaveable { mutableStateOf(initialToken) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Cast, null,
            modifier = Modifier.size(44.dp).align(Alignment.CenterHorizontally),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Pair with your PC",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Easiest way: open http://aepc:8774/pair in this phone's browser and tap the button.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = broker,
            onValueChange = { broker = it },
            label = { Text("Broker") },
            supportingText = { Text("Hostname or IP of the PC running cr-broker") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim() },
            label = { Text("Pairing token") },
            supportingText = { Text("cat ~/.config/cr-remote/token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onPair(broker, token) },
            enabled = broker.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("Connect") }
    }
}
