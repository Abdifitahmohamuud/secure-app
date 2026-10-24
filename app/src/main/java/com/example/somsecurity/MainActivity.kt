package com.example.somsecurity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.example.somsecurity.ui.SecurityDashboardScreen
import com.example.somsecurity.ui.theme.SomSecuriTheme
import com.example.somsecurity.ui.viewmodel.SecurityViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: SecurityViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SomSecuriTheme {
                SecurityDashboardScreen(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAdminStatus()
        viewModel.refreshCameraPermission()
        viewModel.refreshOverlayPermission()
    }
}
