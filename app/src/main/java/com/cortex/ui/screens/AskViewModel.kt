package com.cortex.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cortex.data.AppDatabase
import com.cortex.pipeline.EmbeddingService
import com.cortex.pipeline.QueryService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AskState {
    data object Idle : AskState
    data class Thinking(val question: String) : AskState
    data class Answered(val question: String, val answer: QueryService.Answer) : AskState
    data class Failed(val message: String) : AskState
}

class AskViewModel(app: Application) : AndroidViewModel(app) {

    private val service: QueryService by lazy {
        val dao = AppDatabase.getDatabase(getApplication()).cortexDao()
        QueryService(dao, EmbeddingService.get(getApplication()))
    }

    private val _state = MutableStateFlow<AskState>(AskState.Idle)
    val state: StateFlow<AskState> = _state.asStateFlow()

    fun ask(question: String) {
        _state.value = AskState.Thinking(question)
        viewModelScope.launch {
            _state.value = try {
                val answer = withContext(Dispatchers.IO) { service.ask(question) }
                AskState.Answered(question, answer)
            } catch (t: Throwable) {
                AskState.Failed(t.message ?: "Something went wrong.")
            }
        }
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AskViewModel(app) as T
    }
}
