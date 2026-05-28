package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.data.repository.AuthRepositoryImpl
import com.example.data.repository.StorageRepositoryImpl
import com.example.presentation.auth.AuthScreen
import com.example.presentation.auth.AuthViewModel
import com.example.presentation.dashboard.DashboardScreen
import com.example.presentation.dashboard.DashboardViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    
    private val authRepository by lazy { AuthRepositoryImpl(applicationContext) }
    private val storageRepository by lazy { StorageRepositoryImpl() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            MyApplicationTheme {
                var currentSessionDemoMode by remember { mutableStateOf<Boolean?>(null) }
                
                // Initialize ViewModels inside compose scope securely
                val authViewModel = remember { AuthViewModel(authRepository) }

                if (currentSessionDemoMode == null) {
                    AuthScreen(
                        viewModel = authViewModel,
                        onAuthSuccess = { isDemo ->
                            currentSessionDemoMode = isDemo
                        }
                    )
                } else {
                    val dashboardViewModel = remember { DashboardViewModel(storageRepository) }
                    DashboardScreen(
                        viewModel = dashboardViewModel,
                        isDemoMode = currentSessionDemoMode == true,
                        onSignOut = {
                            authViewModel.signOut()
                            currentSessionDemoMode = null
                        }
                    )
                }
            }
        }
    }
}
