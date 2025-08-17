package com.app.geotagvideocamera.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.geotagvideocamera.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by vm.state.collectAsState()
    val grouped = remember { SettingsSpecs.groupBy { it.category } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    TextButton(onClick = onClose) {
                        Text(text = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingCategory.entries.forEach { cat ->
                val specs = grouped[cat].orEmpty()
                if (specs.isEmpty()) return@forEach

                item {
                    Text(
                        text = stringResource(cat.titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                items(specs, key = { it.id }) { spec ->
                    val enabled = state.isEnabled(spec)
                    when (spec) {
                        is ToggleSpec -> ToggleRow(
                            title = stringResource(spec.titleRes),
                            checked = readToggle(state, spec.id),
                            enabled = enabled
                        ) { vm.update(spec, it) }

                        is DropdownSpec -> DropdownRow(
                            title = stringResource(spec.titleRes),
                            entries = spec.entries.map { stringResource(it) },
                            selectedIndex = readInt(state, spec.id),
                            enabled = enabled
                        ) { vm.update(spec, it) }

                        is SliderSpec -> SliderRow(
                            title = stringResource(spec.titleRes),
                            value = readFloat(state, spec.id),
                            min = spec.min, max = spec.max, step = spec.step,
                            enabled = enabled
                        ) { vm.update(spec, it) }

                        is TextSpec -> {
                            // Show keys/URL fields; simple input dialog
                            TextRow(
                                title = stringResource(spec.titleRes),
                                value = readString(state, spec.id),
                                enabled = enabled
                            ) { vm.update(spec, it) }
                        }
                    }
                }

                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable private fun readToggle(s: SettingsState, id: String) = when (id) {
    "dynamicColors" -> s.dynamicColors
    "compactUi" -> s.compactUi
    "showMap" -> s.showMap
    "showCoordinates" -> s.showCoordinates
    "showAddress" -> s.showAddress
    "showSpeed" -> s.showSpeed
    "showGpsStatus" -> s.showGpsStatus
    "hideModeButton" -> s.hideModeButton
    "debugLocation" -> s.debugLocation
    "showTopBar" -> s.showTopBar
    else -> false
}

@Composable private fun readInt(s: SettingsState, id: String) = when (id) {
    "themeMode" -> s.themeMode
    "unitsIndex" -> s.unitsIndex
    "mapProviderIndex" -> s.mapProviderIndex
    "addressPositionIndex" -> s.addressPositionIndex
    else -> 0
}

@Composable private fun readFloat(s: SettingsState, id: String) = when (id) {
    "mapZoom" -> s.mapZoom
    else -> 0f
}

@Composable private fun readString(s: SettingsState, id: String) = when (id) {
    "styleUrl" -> s.styleUrl
    "maptilerApiKey" -> s.maptilerApiKey
    "geoapifyApiKey" -> s.geoapifyApiKey
    else -> ""
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Switch(checked = checked, enabled = enabled, onCheckedChange = { onChange(it) })
        }
    }
}

@Composable
private fun DropdownRow(
    title: String,
    entries: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelected: (Int) -> Unit
) {
    var show by remember { mutableStateOf(false) }

    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
        ListItem(
            headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    entries.getOrNull(selectedIndex) ?: "",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            trailingContent = {
                TextButton(enabled = enabled, onClick = { show = true }) {
                    Text(stringResource(R.string.action_select))
                }
            }
        )
    }

    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = {},
            title = { Text(title) },
            shape = MaterialTheme.shapes.extraLarge,
            text = {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 2.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        entries.forEachIndexed { i, label ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                tonalElevation = 0.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                            ) {
                                ListItem(
                                    headlineContent = {
                                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    trailingContent = {
                                        RadioButton(
                                            selected = i == selectedIndex,
                                            onClick = {
                                                onSelected(i)
                                                show = false
                                            }
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onSelected(i)
                                            show = false
                                        }
                                )
                            }
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    enabled: Boolean,
    onValue: (Float) -> Unit
) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Slider(
                value = v,
                onValueChange = { raw ->
                    val steps = ((raw - min) / step).toInt()
                    v = (min + steps * step).coerceIn(min, max)
                },
                onValueChangeFinished = { onValue(v) },
                valueRange = min..max,
                steps = ((max - min) / step).toInt() - 1,
                enabled = enabled
            )
            Text(String.format("%.0f", v))
        }
    }
}

@Composable
private fun TextRow(
    title: String,
    value: String,
    enabled: Boolean,
    onValue: (String) -> Unit
) {
    var show by remember { mutableStateOf(false) }
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(if (value.isBlank()) "—" else "••••••••") },
            trailingContent = { TextButton(enabled = enabled, onClick = { show = true }) { Text(stringResource(R.string.action_apply)) } }
        )
    }
    if (show) {
        var input by remember(value) { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.enter_api_key)) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = { onValue(input); show = false }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { show = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}