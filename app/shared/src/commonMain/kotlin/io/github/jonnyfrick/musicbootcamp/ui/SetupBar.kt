package io.github.jonnyfrick.musicbootcamp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Title bar with the setup switcher (Java: File menu → Load / Save / Save As). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetupBar(controller: AppController) {
    var setupMenu by remember { mutableStateOf(false) }
    var actionsMenu by remember { mutableStateOf(false) }
    var saveAsDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text("MusicBootCamp") },
        actions = {
            Box {
                TextButton(onClick = { setupMenu = true }, enabled = !controller.running) {
                    Text("Setup: ${controller.setupName} ▾")
                }
                DropdownMenu(expanded = setupMenu, onDismissRequest = { setupMenu = false }) {
                    controller.setupNames.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(if (name == controller.setupName) "✓ $name" else name) },
                            onClick = {
                                setupMenu = false
                                controller.selectSetup(name)
                            },
                        )
                    }
                }
            }
            Box {
                TextButton(onClick = { actionsMenu = true }, enabled = !controller.running) { Text("⋮") }
                DropdownMenu(expanded = actionsMenu, onDismissRequest = { actionsMenu = false }) {
                    DropdownMenuItem(text = { Text("Save setup as…") }, onClick = {
                        actionsMenu = false
                        saveAsDialog = true
                    })
                    if (controller.canImportLegacyFiles) {
                        DropdownMenuItem(text = { Text("Import setup from MusicBootCamp (Java)…") }, onClick = {
                            actionsMenu = false
                            controller.importLegacySetup()
                        })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Delete this setup…") }, onClick = {
                        actionsMenu = false
                        deleteDialog = true
                    })
                }
            }
        },
    )

    if (saveAsDialog) {
        var name by remember { mutableStateOf("${controller.setupName} copy") }
        AlertDialog(
            onDismissRequest = { saveAsDialog = false },
            title = { Text("Save setup as") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    saveAsDialog = false
                    controller.saveSetupAs(name)
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saveAsDialog = false }) { Text("Cancel") } },
        )
    }

    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("Delete '${controller.setupName}'?") },
            text = { Text("Its settings and all its learned sequences are deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteDialog = false
                    controller.deleteCurrentSetup()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("Cancel") } },
        )
    }
}
