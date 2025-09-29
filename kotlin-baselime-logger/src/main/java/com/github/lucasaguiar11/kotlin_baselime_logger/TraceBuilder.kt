package com.github.lucasaguiar11.kotlin_baselime_logger

/**
 * TraceBuilder V4 - Estrutura hierárquica real sem duplicação
 *
 * Pensa em termos de árvore:
 * - RootSpan (pai)
 *   - ChildSpan 1
 *   - ChildSpan 2
 *     - GrandchildSpan 1
 *     - GrandchildSpan 2
 */
class TraceBuilder(private val rootSpanName: String) {
    private val rootSpan = SpanDefinition(rootSpanName)

    var attributes: Map<String, Any>? = null
        set(value) {
            field = value
            rootSpan.attributes = value
        }

    fun span(name: String, block: SpanDefinition.() -> Unit = {}): SpanDefinition {
        val childSpan = SpanDefinition(name)
        childSpan.block()
        rootSpan.addChild(childSpan)
        return childSpan
    }

    internal fun execute() {
        val startTime = System.currentTimeMillis()

        // Executa a árvore completa a partir da raiz
        rootSpan.execute(null, startTime)
    }
}

/**
 * Definição de um span na árvore hierárquica
 */
class SpanDefinition(val name: String) {
    var duration: Long = 100 // padrão 100ms
    var attributes: Map<String, Any>? = null
    var status: SpanStatus = SpanStatus.OK

    private val children = mutableListOf<SpanDefinition>()

    fun addChild(child: SpanDefinition) {
        children.add(child)
    }

    fun childSpan(name: String, block: SpanDefinition.() -> Unit = {}): SpanDefinition {
        val child = SpanDefinition(name)
        child.block()
        addChild(child)
        return child
    }

    /**
     * Executa este span e todos os filhos com durações customizadas
     */
    internal fun execute(parentContext: SpanContext?, baseStartTime: Long) {
        // Cria span com timestamp customizado de início
        val spanContext = Logger.startSpan(
            name,
            attributes ?: emptyMap(),
            parentContext,
            baseStartTime
        )

        // Calcula duração real baseada nos filhos ou usa duration definida
        val actualDuration = if (children.isNotEmpty()) {
            children.sumOf { it.getTotalDuration() }
        } else {
            duration
        }

        // Executa filhos sequencialmente
        var childOffset = 0L
        children.forEach { child ->
            child.execute(spanContext, baseStartTime + childOffset)
            childOffset += child.getTotalDuration()
        }

        // Finaliza span com timestamp customizado de fim
        val endTime = baseStartTime + actualDuration
        Logger.endSpan(
            spanContext.copy(customEndTimeMs = endTime),
            null,
            status
        )
    }

    /**
     * Calcula duração total incluindo todos os filhos
     */
    private fun getTotalDuration(): Long {
        return if (children.isNotEmpty()) {
            children.sumOf { it.getTotalDuration() }
        } else {
            duration
        }
    }
}

// Extensão no Logger para criar traces
fun Logger.createTrace(name: String, block: TraceBuilder.() -> Unit) {
    val traceBuilder = TraceBuilder(name)
    traceBuilder.block()
    traceBuilder.execute()
}