package com.example.sanporoad
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class WalkViewModel(application: Application): AndroidViewModel(application) {
    val store=RouteStore(application)
    private val mutableRoutes=MutableStateFlow<List<SavedRoute>>(emptyList())
    val routes: StateFlow<List<SavedRoute>> = mutableRoutes.asStateFlow()
    private val mutableError=MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()
    private var loading: Job? = null
    fun refresh() {
        loading?.cancel()
        loading=viewModelScope.launch {
            try { mutableRoutes.value=withContext(Dispatchers.IO){store.getAll()};mutableError.value=null }
            catch(e:CancellationException){throw e}
            catch(e:Exception){mutableError.value="記録を読み込めませんでした。再試行してください。"}
        }
    }
}

