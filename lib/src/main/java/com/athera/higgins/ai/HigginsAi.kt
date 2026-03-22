package com.athera.higgins.ai

import android.content.Context
import com.athera.higgins.ai.internal.InferenceEngineImpl

/**
 * Main entry point for the Higgins AI library.
 */
object HigginsAi {
    /**
     * Get the inference engine single instance.
     */
    fun getInferenceEngine(context: Context) = InferenceEngineImpl.getInstance(context)
}
