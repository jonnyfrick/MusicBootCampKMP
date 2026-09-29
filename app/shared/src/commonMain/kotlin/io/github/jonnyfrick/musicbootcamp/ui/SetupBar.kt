package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.jonnyfrick.musicbootcamp.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The top app bar of the screens that belong to a setup (Practice, Memory): the setup's name as
 * title, which switches setups (like an account switcher), and its actions in the overflow menu
 * (Java: File menu → Load / Save / Save As).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetupTopBar(
    controller: AppController,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { SetupSwitcher(controller) },
        actions = {
            actions()
            SetupMenu(controller)
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun SetupSwitcher(controller: AppController) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(Res.string.setup_choose, controller.setupName)
    Box {
        TextButton(
            onClick = { open = true },
            enabled = !controller.running,
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    controller.setupName,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Icon(AppIcons.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            controller.setupNames.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    leadingIcon = { if (name == controller.setupName) Icon(AppIcons.Check, contentDescription = null) },
                    onClick = {
                        open = false
                        controller.selectSetup(name)
                    },
                )
            }
        }
    }
}

@Composable
private fun SetupMenu(controller: AppController) {
    var menu by remember { mutableStateOf(false) }
    var saveAsDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { menu = true }, enabled = !controller.running) {
            Icon(AppIcons.MoreVert, contentDescription = stringResource(Res.string.more_options))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(Res.string.setup_save_as)) }, onClick = {
                menu = false
                saveAsDialog = true
            })
            if (controller.canImportLegacyFiles) {
                DropdownMenuItem(text = { Text(stringResource(Res.string.setup_import)) }, onClick = {
                    menu = false
                    controller.importLegacySetup()
                })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(Res.string.setup_delete)) }, onClick = {
                menu = false
                deleteDialog = true
            })
        }
    }

    if (saveAsDialog) {
        val suggestion = stringResource(Res.string.setup_copy_name, controller.setupName)
        var name by remember { mutableStateOf(suggestion) }
        AlertDialog(
            onDismissRequest = { saveAsDialog = false },
            title = { Text(stringResource(Res.string.setup_save_as_title)) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(Res.string.setup_name)) }, singleLine = true)
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    saveAsDialog = false
                    controller.saveSetupAs(name)
                }) { Text(stringResource(Res.string.save)) }
            },
            dismissButton = { TextButton(onClick = { saveAsDialog = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }

    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text(stringResource(Res.string.setup_delete_title, controller.setupName)) },
            text = { Text(stringResource(Res.string.setup_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteDialog = false
                    controller.deleteCurrentSetup()
                }) { Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}
