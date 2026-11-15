package tech.astrix.droidvm

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tech.astrix.droidvm.data.Repository
import tech.astrix.droidvm.engine.NativeEnv
import tech.astrix.droidvm.engine.VmManager
import tech.astrix.droidvm.engine.VmService

sealed interface EngineState {
    data class Preparing(val step: String) : EngineState
    data object Ready : EngineState
    data class Failed(val message: String) : EngineState
}

class DroidVmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        tech.astrix.droidvm.i18n.I18n.init(this)
        repo = Repository(this)
        VmManager.init(this, repo)
        VmService.ensureChannel(this)
        Thread({
            NativeEnv.init(this) { _engine.value = EngineState.Preparing(it) }
            _engine.value = if (NativeEnv.ready) EngineState.Ready else EngineState.Failed(NativeEnv.error ?: "Unknown error")
        }, "engine-init").start()
    }

    companion object {
        lateinit var instance: DroidVmApp; private set
        lateinit var repo: Repository; private set
        private val _engine = MutableStateFlow<EngineState>(EngineState.Preparing("Starting"))
        val engine: StateFlow<EngineState> = _engine
    }
}
