package com.example.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.repository.AuthRepository
import com.example.domain.model.UserSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class AuthUiState {
    object Idle : AuthUiState()
    object Loading : AuthUiState()
    data class Success(val session: UserSession) : AuthUiState()
    data class Error(val message: String) : AuthUiState()
}

class AuthViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _isDemoMode = MutableStateFlow(false)
    val isDemoMode: StateFlow<Boolean> = _isDemoMode.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.initializeSession()
            val session = authRepository.currentSession.value
            if (session != null) {
                _uiState.value = AuthUiState.Success(session)
            }
        }
    }

    fun signIn(email: String, password: String) {
        if (email.trim() == "demo@storagepool.com" && password == "demo123") {
            enableDemoMode()
            return
        }
        _uiState.value = AuthUiState.Loading
        viewModelScope.launch {
            val result = authRepository.signIn(email, password)
            result.fold(
                onSuccess = { session ->
                    _isDemoMode.value = false
                    _uiState.value = AuthUiState.Success(session)
                },
                onFailure = { error ->
                    _uiState.value = AuthUiState.Error(error.message ?: "Authentication failed")
                }
            )
        }
    }

    fun signUp(email: String, password: String) {
        _uiState.value = AuthUiState.Loading
        viewModelScope.launch {
            val result = authRepository.signUp(email, password)
            result.fold(
                onSuccess = { session ->
                    _isDemoMode.value = false
                    _uiState.value = AuthUiState.Success(session)
                },
                onFailure = { error ->
                    _uiState.value = AuthUiState.Error(error.message ?: "Registration failed")
                }
            )
        }
    }

    fun enableDemoMode() {
        _isDemoMode.value = true
        val demoSession = UserSession("demo-token-123456", "demo-user-id", "demo@storagepool.com")
        _uiState.value = AuthUiState.Success(demoSession)
    }

    fun signOut() {
        _uiState.value = AuthUiState.Idle
        viewModelScope.launch {
            if (_isDemoMode.value) {
                _isDemoMode.value = false
            } else {
                authRepository.signOut()
            }
        }
    }
}
