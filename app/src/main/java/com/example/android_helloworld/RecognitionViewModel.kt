package com.example.android_helloworld

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.android_helloworld.db.RecognitionResult
import com.example.android_helloworld.db.UserDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ViewModel to hold and manage UI-related data.
class RecognitionViewModel(private val dao: UserDao) : ViewModel() {
    val recognitionResults: StateFlow<List<RecognitionResult>> = MutableStateFlow(emptyList())
    // Expose the list of results as a StateFlow.
    // The UI will collect this flow and update automatically.
//    val recognitionResults: StateFlow<List<RecognitionResult>> = dao.getAllRecognitionResults()
//        .stateIn(
//            scope = viewModelScope,
//            started = SharingStarted.WhileSubscribed(5000L),
//            initialValue = emptyList()
//        )

    /**
     * Clears all recognition history from the database.
     */
    fun clearHistory() {
        viewModelScope.launch {
            dao.clearAllResults()
        }
    }
}

// Factory to create an instance of the ViewModel with the Dao.
class RecognitionViewModelFactory(private val dao: UserDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RecognitionViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RecognitionViewModel(dao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
