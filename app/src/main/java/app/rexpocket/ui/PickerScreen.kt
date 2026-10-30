package app.rexpocket.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rexpocket.core.Phase
import app.rexpocket.core.PocketState
import app.rexpocket.core.Trouble
import app.rexpocket.R
import app.rexpocket.net.TailscaleStatus

// A host's session list, drawn from the cache until the connection has the live one.

@Composable
internal fun PickerScreen(
    hostLabel: String,
    state: PocketState,
    tailscaleStatus: TailscaleStatus,
    tailscaleEnabled: Boolean,
    onTailscale: () -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    opening: Opening?,
    onOpen: (String) -> Unit,
    onCreate: (String?) -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeaderButton("‹", spoken = stringResource(R.string.action_back), size = TextStyles.display, onClick = onBack)
            Column(Modifier.weight(1f)) {
                Text(hostLabel, color = Colors.ink, fontSize = TextStyles.title, fontWeight = FontWeight.SemiBold)
                Text(phaseText(state), color = if (state.phase == Phase.Live) Colors.accent else Colors.amber, fontSize = TextStyles.label)
            }
        }
        // A retry that fails and succeeds again within a second is noise; a real failure stays.
        val problem = rememberSettledProblem(state.phase, state.problem)
        if (problem != null && problem.trouble != Trouble.TailnetNotJoined) {
            Row(Modifier.padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    problemText(problem, state.endpointLabel),
                    color = Colors.danger,
                    fontSize = TextStyles.label,
                    modifier = Modifier.weight(1f),
                )
                if (state.phase == Phase.Connecting || state.phase == Phase.Reconnecting) {
                    Text(
                        stringResource(R.string.action_retry_now),
                        color = Colors.accent,
                        fontSize = TextStyles.label,
                        modifier = Modifier.clip(RoundedCornerShape(Radius.chip)).clickable(onClick = onRetry)
                            .padding(horizontal = Space.s, vertical = Space.s),
                    )
                }
            }
        }
        // The two reasons this list can stay empty, and for both of them the fix is one tap away.
        if (!tailscaleEnabled && state.problem?.trouble == Trouble.TailnetNotJoined) {
            Text(
                stringResource(R.string.tailscale_need_join),
                color = Colors.amber,
                fontSize = TextStyles.label,
                modifier = Modifier.padding(top = 8.dp).clickable(onClick = onTailscale),
            )
        } else if (tailscaleStatus.authUrl.isNotBlank()) {
            Text(
                stringResource(R.string.tailscale_login_banner),
                color = Colors.amber,
                fontSize = TextStyles.label,
                modifier = Modifier.padding(top = 8.dp).clickable { openLink(context, tailscaleStatus.authUrl) },
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.sessions), color = Colors.muted, fontSize = TextStyles.label)
        Spacer(Modifier.height(8.dp))
        if (state.sessions.isEmpty()) {
            Text(
                stringResource(
                    if (state.phase == Phase.Live) R.string.session_none_live else R.string.session_loading,
                ),
                color = Colors.muted,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f, fill = false)) {
                items(state.sessions, key = { it.id }) { session ->
                    val windows = state.views[session.id]?.windows?.size
                    EntryCard(
                        title = session.label,
                        // No filler under the name: the count when it is known, nothing when it is not.
                        detail = windows?.let { stringResource(R.string.session_window_count, it) }.orEmpty(),
                        tags = emptyList(),
                        // A tap before the connection is up waits for it; the row says so.
                        trailing = stringResource(
                            if (opening == Opening.Existing(session.id)) R.string.session_opening else R.string.session_enter,
                        ),
                        onOpen = { onOpen(session.id) },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(if (opening == Opening.New) R.string.session_creating else R.string.session_new),
            color = Colors.onAccent,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 18.dp)
                .clip(RoundedCornerShape(Radius.card)).background(Colors.accent)
                .clickable(enabled = opening == null) { onCreate(null) }
                .padding(vertical = 14.dp),
            fontWeight = FontWeight.SemiBold,
        )
    }
}
