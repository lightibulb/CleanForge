package com.cleanforge.domain.usecase

import com.cleanforge.core.cleaning.CleaningEngine
import com.cleanforge.core.cleaning.CleaningProgress
import com.cleanforge.core.model.ConfirmedPlan
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Takes a [ConfirmedPlan] only: there is no overload that accepts raw scan results. */
class ExecuteCleaningPlanUseCase @Inject constructor(private val engine: CleaningEngine) {
    operator fun invoke(plan: ConfirmedPlan): Flow<CleaningProgress> = engine.execute(plan)
}
