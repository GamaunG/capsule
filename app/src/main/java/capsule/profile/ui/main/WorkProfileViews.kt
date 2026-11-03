package capsule.profile.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import capsule.profile.R
import capsule.profile.ui.icons.add
import capsule.profile.ui.icons.lock
import capsule.profile.ui.icons.settings

/** Thin notice shown under the app bar while the profile is unusable. */
@Composable
fun WorkProfileBanner(
    paused: Boolean,
    onResume: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Icon(
                imageVector = if (paused) lock else settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text =
                        stringResource(
                            if (paused) {
                                R.string.work_profile_paused_title
                            } else {
                                R.string.work_profile_unreachable_title
                            },
                        ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text =
                        stringResource(
                            if (paused) {
                                R.string.work_profile_paused_text
                            } else {
                                R.string.work_profile_unreachable_text
                            },
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Surface(
                shape = RoundedCornerShape(100.dp),
                color = MaterialTheme.colorScheme.primary,
                onClick = if (paused) onResume else onOpenSettings,
            ) {
                Text(
                    text =
                        stringResource(
                            if (paused) {
                                R.string.work_profile_resume
                            } else {
                                R.string.work_profile_open_settings
                            },
                        ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** Full-tab message shown where the work app list would be. */
@Composable
fun WorkProfilePlaceholder(
    paused: Boolean,
    unreachable: Boolean,
    absent: Boolean,
    onResume: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartSetup: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector =
                    when {
                        absent -> add
                        paused -> lock
                        else -> settings
                    },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text =
                    stringResource(
                        when {
                            absent -> R.string.work_profile_absent_title
                            paused -> R.string.work_profile_paused_title
                            else -> R.string.work_profile_unreachable_title
                        },
                    ),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text =
                    stringResource(
                        when {
                            absent -> R.string.work_profile_absent_text
                            paused -> R.string.work_profile_paused_text
                            else -> R.string.work_profile_unreachable_text
                        },
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(100.dp),
                color = MaterialTheme.colorScheme.primary,
                onClick =
                    when {
                        absent -> onStartSetup
                        paused -> onResume
                        else -> onOpenSettings
                    },
            ) {
                Text(
                    text =
                        stringResource(
                            when {
                                absent -> R.string.work_profile_start_setup
                                paused -> R.string.work_profile_resume
                                else -> R.string.work_profile_open_settings
                            },
                        ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
    }
}
