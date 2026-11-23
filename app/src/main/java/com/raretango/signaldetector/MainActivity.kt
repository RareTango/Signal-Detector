package com.raretango.signaldetector

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.raretango.signaldetector.ui.Accent
import com.raretango.signaldetector.ui.DirectionScreen
import com.raretango.signaldetector.ui.Panel
import com.raretango.signaldetector.ui.ScannerScreen
import com.raretango.signaldetector.ui.SettingsScreen
import com.raretango.signaldetector.ui.WhoScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    /** The "RTL-SDR driver" app owns the USB dongle and serves rtl_tcp on 127.0.0.1:1234. */
    private val usbDriver = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            vm.connect()
        } else {
            val why = r.data?.getStringExtra("detailed_exception_message")
                ?: "RTL-SDR driver did not start. Is the dongle plugged in (USB-OTG) and permission granted?"
            vm.onUsbDriverFailed(why)
        }
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) vm.startLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            vm.startLocation()
        } else {
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent, background = Color(0xFF0B1220), surface = Color(0xFF0B1220),
                    surfaceContainer = Panel, onPrimary = Color(0xFF0B1220),
                ),
            ) {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Scaffold(
                    containerColor = Color(0xFF0B1220),
                    bottomBar = {
                        NavigationBar(containerColor = Panel) {
                            val items = listOf(
                                "Scanner" to Icons.Filled.Search,
                                "Direction" to Icons.Filled.LocationOn,
                                "Who" to Icons.Filled.Person,
                                "Settings" to Icons.Filled.Settings,
                            )
                            items.forEachIndexed { i, (label, icon) ->
                                NavigationBarItem(
                                    selected = tab == i, onClick = { tab = i },
                                    icon = { Icon(icon, contentDescription = label) }, label = { Text(label) },
                                )
                            }
                        }
                    },
                ) { pad ->
                    Box(Modifier.padding(pad)) {
                        when (tab) {
                            0 -> ScannerScreen(vm, onOpenSettings = { tab = 3 })
                            1 -> DirectionScreen(vm)
                            2 -> WhoScreen(vm)
                            else -> SettingsScreen(vm, onConnect = ::connect)
                        }
                    }
                }
            }
        }
    }

    private fun connect() {
        if (vm.settings.value.source != SourceKind.USB) { vm.connect(); return }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("iqsrc://-a 127.0.0.1 -p 1234 -n 1 -s 2048000"))
        try {
            usbDriver.launch(intent)
        } catch (e: ActivityNotFoundException) {
            vm.onUsbDriverFailed("Install the free \"RTL-SDR driver\" app from Google Play, then tap Connect again.")
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=marto.rtl_tcp_andro")))
            } catch (_: ActivityNotFoundException) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=marto.rtl_tcp_andro")))
            }
        }
    }
}
