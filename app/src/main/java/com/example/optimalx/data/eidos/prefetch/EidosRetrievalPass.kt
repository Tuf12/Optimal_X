package com.example.optimalx.data.eidos.prefetch

data class EidosRetrievalPass(
    val scopeMode: String,
    val scopeId: Long? = null,
    /** When non-empty, only hits in these corpora are kept (typically a global memory pass). */
    val corpora: Set<EidosMemoryCorpus> = emptySet(),
    val perPassLimit: Int = 8,
)
