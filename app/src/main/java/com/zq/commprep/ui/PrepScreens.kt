package com.zq.commprep.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.zq.commprep.domain.ConversationType
import com.zq.commprep.domain.OpeningBuilder
import com.zq.commprep.domain.OpeningInputs
import com.zq.commprep.domain.PrepDraft

/*
 * Conversation-preparation journey (0.2.0-prep2): choose a conversation → write your own words →
 * local templates assemble an editable opening and request → (optional pulse check-in, breathing,
 * camera-off rehearsal) → takeaway card. Everything here lives in memory for this session only:
 * no storage, no timeline entry, no export or backup, no network, no AI.
 */

@Composable
fun ChooseConversationScreen(onChoose: (ConversationType) -> Unit, onCancel: () -> Unit) {
    Section {
        Title("What conversation are you preparing for?")
        Body("Pick the closest one. You'll write the details in your own words next.")
        ConversationType.entries.forEach { t -> Secondary(t.label, "conv_${t.name.lowercase()}") { onChoose(t) } }
        SmallAction("Back", "conv_back", onClick = onCancel)
    }
}

@Composable
fun BuildOpeningScreen(type: ConversationType, initial: PrepDraft?, onReady: (PrepDraft) -> Unit, onBack: () -> Unit) {
    var situation by remember { mutableStateOf("") }
    var point by remember { mutableStateOf("") }
    var request by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(initial?.takeIf { it.type == type }) }
    val inputs = OpeningInputs(situation, point, request)
    Section {
        Title(type.label)
        Body("Write in your own words. This stays in the app's memory for this session only: it is not saved, " +
            "not added to your timeline or exports, and not sent anywhere.", "prep_privacy_note")
        Field(situation, { situation = it }, type.situationHint + " (optional)", "prep_situation")
        Field(point, { point = it }, type.pointHint, "prep_point")
        Field(request, { request = it }, type.requestHint, "prep_request")
        Primary(if (draft == null) "Build my opening" else "Rebuild from my words", "prep_build",
            enabled = OpeningBuilder.canBuild(inputs)) { draft = OpeningBuilder.build(type, inputs) }
        if (!OpeningBuilder.canBuild(inputs)) Body("Add your main point and your request to build an opening.", "prep_needs_input")
        draft?.let { d ->
            Body("Edit anything you like:")
            Field(d.opening, { draft = d.copy(opening = it) }, "My opening", "prep_opening", single = false)
            Field(d.request, { draft = d.copy(request = it) }, "My request", "prep_request_out", single = false)
            Field(d.ifDifficult, { draft = d.copy(ifDifficult = it) }, "If the conversation becomes difficult…", "prep_difficult", single = false)
            Primary("Continue", "prep_continue", enabled = d.opening.isNotBlank()) { onReady(d) }
        }
        SmallAction("Back", "prep_back", onClick = onBack)
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, tag: String, single: Boolean = true) =
    OutlinedTextField(value, { onChange(it.take(OpeningBuilder.MAX_INPUT_CHARS * 2)) }, label = { Text(label) },
        singleLine = single, modifier = Modifier.fillMaxWidth().testTag(tag))

@Composable
fun TakeawayCardScreen(draft: PrepDraft, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Section {
        Title("Your conversation card")
        Text("My opening", style = MaterialTheme.typography.titleSmall)
        Body(draft.opening, "card_opening")
        Text("My request", style = MaterialTheme.typography.titleSmall)
        Body(draft.request, "card_request")
        Text("If the conversation becomes difficult…", style = MaterialTheme.typography.titleSmall)
        Body(draft.ifDifficult, "card_difficult")
        Primary("Copy to clipboard", "card_copy") { copyToClipboard(ctx, OpeningBuilder.cardText(draft)); copied = true }
        if (copied) Body("Copied. Paste it wherever you like; ZQ Settle keeps no copy.", "card_copied")
        Body("This card is not saved. It disappears when you tap Done or close the app.", "card_session_only")
        Secondary("Done", "card_done", onClick = onDone)
    }
}

/** Copy is the only way the card leaves the screen, and only on the user's tap. Marked sensitive (Android 13+ hides the preview). */
private fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("ZQ Settle conversation card", text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    cm.setPrimaryClip(clip)
}
