package org.bigmeco.nearbylab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.dsl.module

sealed interface LabIntent {
    data class Start(val host: Boolean, val name: String) : LabIntent

    data class Connect(val id: String) : LabIntent

    data class Verify(val id: String, val accept: Boolean) : LabIntent

    data class Disconnect(val id: String) : LabIntent

    data class Send(val text: String, val json: Boolean) : LabIntent

    data object Ping : LabIntent

    data object Stop : LabIntent

    data object Share : LabIntent

    data class Load(val plan: LoadPlan, val suite: Boolean = false, val clients: Boolean = false) :
        LabIntent

    data object CancelLoad : LabIntent

    data class Handoff(val id: String) : LabIntent

    data object TakeOver : LabIntent
}

class LabUseCase(private val repository: LabRepository) {
    val state = repository.state

    fun tick() = repository.tick()

    fun execute(intent: LabIntent) {
        when (intent) {
            is LabIntent.Start -> repository.start(intent.host, intent.name)
            is LabIntent.Connect -> repository.connect(intent.id)
            is LabIntent.Verify -> repository.verify(intent.id, intent.accept)
            is LabIntent.Disconnect -> repository.disconnect(intent.id)
            is LabIntent.Send -> repository.send(intent.text, intent.json)
            LabIntent.Ping -> repository.ping()
            LabIntent.Stop -> repository.stop()
            LabIntent.Share -> repository.share()
            is LabIntent.Load -> repository.load(intent.plan, intent.suite, intent.clients)
            LabIntent.CancelLoad -> repository.cancelLoad()
            is LabIntent.Handoff -> repository.handoff(intent.id)
            LabIntent.TakeOver -> repository.takeOver()
        }
    }
}

class LabViewModel(private val useCase: LabUseCase) : ViewModel() {
    val state = useCase.state

    init {
        viewModelScope.launch {
            while (true) {
                delay(20)
                useCase.tick()
            }
        }
    }

    fun onIntent(intent: LabIntent) = useCase.execute(intent)

    override fun onCleared() {
        useCase.execute(LabIntent.Stop)
    }
}

private lateinit var labKoin: Koin

internal fun createLabViewModel(): LabViewModel = labKoin.get()

fun initializeLab(transport: NearbyTransport) {
    labKoin =
        startKoin {
                modules(
                    module {
                        single<NearbyTransport> { transport }
                        single<LabRepository> { LabRepositoryImpl(get()) }
                        single { LabUseCase(get()) }
                        factory { LabViewModel(get()) }
                    },
                )
            }
            .koin
}
