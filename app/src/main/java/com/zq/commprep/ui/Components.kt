package com.zq.commprep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun Primary(text: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) =
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag)) { Text(text) }

@Composable
fun Secondary(text: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag)) { Text(text) }

@Composable
fun SmallAction(text: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) =
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) { Text(text) }

@Composable
fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp))

@Composable
fun Body(text: String, tag: String? = null) =
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)

@Composable
fun Why(text: String) = Text("Why it helps: $text", style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun ButtonRow(content: @Composable () -> Unit) =
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { content() }

@Composable
fun Section(content: @Composable () -> Unit) =
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) { content() }
