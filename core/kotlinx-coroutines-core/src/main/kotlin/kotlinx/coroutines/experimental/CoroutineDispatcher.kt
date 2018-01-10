/*
 * Copyright 2016-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kotlinx.coroutines.experimental

import kotlinx.coroutines.experimental.internal.*
import kotlin.coroutines.experimental.AbstractCoroutineContextElement
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.ContinuationInterceptor
import kotlin.coroutines.experimental.CoroutineContext

/**
 * Base class that shall be extended by all coroutine dispatcher implementations.
 *
 * The following standard implementations are provided by `kotlinx.coroutines`:
 * * [Unconfined] -- starts coroutine execution in the current call-frame until the first suspension.
 *   On first  suspension the coroutine builder function returns.
 *   The coroutine will resume in whatever thread that is used by the
 *   corresponding suspending function, without confining it to any specific thread or pool.
 *   This in an appropriate choice for IO-intensive coroutines that do not consume CPU resources.
 * * [DefaultDispatcher] -- is used by all standard builder if no dispatcher nor any other [ContinuationInterceptor]
 *   is specified in their context. It is currently equal to [CommonPool] (subject to change).
 * * [CommonPool] -- immediately returns from the coroutine builder and schedules coroutine execution to
 *   a common pool of shared background threads.
 *   This is an appropriate choice for compute-intensive coroutines that consume a lot of CPU resources.
 * * Private thread pools can be created with [newSingleThreadContext] and [newFixedThreadPoolContext].
 * * An arbitrary [Executor][java.util.concurrent.Executor] can be converted to dispatcher with [asCoroutineDispatcher] extension function.
 *
 * This class ensures that debugging facilities in [newCoroutineContext] function work properly.
 */
public actual abstract class CoroutineDispatcher actual constructor() :
        AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
    /**
     * Returns `true` if execution shall be dispatched onto another thread.
     * The default behaviour for most dispatchers is to return `true`.
     *
     * UI dispatchers _should not_ override `isDispatchNeeded`, but leave a default implementation that
     * returns `true`. To understand the rationale beyond this recommendation, consider the following code:
     *
     * ```kotlin
     * fun asyncUpdateUI() = async(MainThread) {
     *     // do something here that updates something in UI
     * }
     * ```
     *
     * When you invoke `asyncUpdateUI` in some background thread, it immediately continues to the next
     * line, while UI update happens asynchronously in the UI thread. However, if you invoke
     * it in the UI thread itself, it updates UI _synchronously_ if your `isDispatchNeeded` is
     * overridden with a thread check. Checking if we are already in the UI thread seems more
     * efficient (and it might indeed save a few CPU cycles), but this subtle and context-sensitive
     * difference in behavior makes the resulting async code harder to debug.
     *
     * Basically, the choice here is between "JS-style" asynchronous approach (async actions
     * are always postponed to be executed later in the even dispatch thread) and "C#-style" approach
     * (async actions are executed in the invoker thread until the first suspension point).
     * While, C# approach seems to be more efficient, it ends up with recommendations like
     * "use `yield` if you need to ....". This is error-prone. JS-style approach is more consistent
     * and does not require programmers to think about whether they need to yield or not.
     *
     * However, coroutine builders like [launch] and [async] accept an optional [CoroutineStart]
     * parameter that allows one to optionally choose C#-style [CoroutineStart.UNDISPATCHED] behaviour
     * whenever it is needed for efficiency.
     */
    public actual open fun isDispatchNeeded(context: CoroutineContext): Boolean = true

    /**
     * Dispatches execution of a runnable [block] onto another thread in the given [context].
     */
    public actual abstract fun dispatch(context: CoroutineContext, block: Runnable)

    /**
     * Returns continuation that wraps the original [continuation], thus intercepting all resumptions.
     */
    public actual override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> =
            DispatchedContinuation(this, continuation)

    /**
     * @suppress **Error**: Operator '+' on two CoroutineDispatcher objects is meaningless.
     * CoroutineDispatcher is a coroutine context element and `+` is a set-sum operator for coroutine contexts.
     * The dispatcher to the right of `+` just replaces the dispatcher the left of `+`.
     */
    @Suppress("DeprecatedCallableAddReplaceWith")
    @Deprecated(message = "Operator '+' on two CoroutineDispatcher objects is meaningless. " +
            "CoroutineDispatcher is a coroutine context element and `+` is a set-sum operator for coroutine contexts. " +
            "The dispatcher to the right of `+` just replaces the dispatcher the left of `+`.",
            level = DeprecationLevel.ERROR)
    public operator fun plus(other: CoroutineDispatcher) = other

    // for nicer debugging
    override fun toString(): String =
        "${this::class.java.simpleName}@${Integer.toHexString(System.identityHashCode(this))}"

}

/**
 * A runnable task for [CoroutineDispatcher.dispatch].
 */
public actual typealias Runnable = java.lang.Runnable

@Suppress("PrivatePropertyName")
private val UNDEFINED = Symbol("UNDEFINED")

internal class DispatchedContinuation<in T>(
    @JvmField val dispatcher: CoroutineDispatcher,
    @JvmField val continuation: Continuation<T>
): Continuation<T> by continuation, DispatchedTask<T> {
    private var _state: Any? = UNDEFINED
    public override var resumeMode: Int = 0

    override fun takeState(): Any? {
        val state = _state
        check(state !== UNDEFINED) // fail-fast if repeatedly invoked
        _state = UNDEFINED
        return state
    }

    override val delegate: Continuation<T>
        get() = this

    override fun resume(value: T) {
        val context = continuation.context
        if (dispatcher.isDispatchNeeded(context)) {
            _state = value
            resumeMode = MODE_ATOMIC_DEFAULT
            dispatcher.dispatch(context, this)
        } else
            resumeUndispatched(value)
    }

    override fun resumeWithException(exception: Throwable) {
        val context = continuation.context
        if (dispatcher.isDispatchNeeded(context)) {
            _state = CompletedExceptionally(exception)
            resumeMode = MODE_ATOMIC_DEFAULT
            dispatcher.dispatch(context, this)
        } else
            resumeUndispatchedWithException(exception)
    }

    @Suppress("NOTHING_TO_INLINE") // we need it inline to save us an entry on the stack
    inline fun resumeCancellable(value: T) {
        val context = continuation.context
        if (dispatcher.isDispatchNeeded(context)) {
            _state = value
            resumeMode = MODE_CANCELLABLE
            dispatcher.dispatch(context, this)
        } else
            resumeUndispatched(value)
    }

    @Suppress("NOTHING_TO_INLINE") // we need it inline to save us an entry on the stack
    inline fun resumeCancellableWithException(exception: Throwable) {
        val context = continuation.context
        if (dispatcher.isDispatchNeeded(context)) {
            _state = CompletedExceptionally(exception)
            resumeMode = MODE_CANCELLABLE
            dispatcher.dispatch(context, this)
        } else
            resumeUndispatchedWithException(exception)
    }

    @Suppress("NOTHING_TO_INLINE") // we need it inline to save us an entry on the stack
    inline fun resumeUndispatched(value: T) {
        withCoroutineContext(context) {
            continuation.resume(value)
        }
    }

    @Suppress("NOTHING_TO_INLINE") // we need it inline to save us an entry on the stack
    inline fun resumeUndispatchedWithException(exception: Throwable) {
        withCoroutineContext(context) {
            continuation.resumeWithException(exception)
        }
    }

    // used by "yield" implementation
    internal fun dispatchYield(value: T) {
        val context = continuation.context
        _state = value
        resumeMode = MODE_CANCELLABLE
        dispatcher.dispatch(context, this)
    }

    override fun toString(): String =
        "DispatchedContinuation[$dispatcher, ${continuation.toDebugString()}]"
}

internal fun <T> Continuation<T>.resumeCancellable(value: T) = when (this) {
    is DispatchedContinuation -> resumeCancellable(value)
    else -> resume(value)
}

internal fun <T> Continuation<T>.resumeCancellableWithException(exception: Throwable) = when (this) {
    is DispatchedContinuation -> resumeCancellableWithException(exception)
    else -> resumeWithException(exception)
}

internal fun <T> Continuation<T>.resumeDirect(value: T) = when (this) {
    is DispatchedContinuation -> continuation.resume(value)
    else -> resume(value)
}

internal fun <T> Continuation<T>.resumeDirectWithException(exception: Throwable) = when (this) {
    is DispatchedContinuation -> continuation.resumeWithException(exception)
    else -> resumeWithException(exception)
}

/**
 * @suppress **This is unstable API and it is subject to change.**
 */
public interface DispatchedTask<in T> : Runnable {
    public val delegate: Continuation<T>
    public val resumeMode: Int get() = MODE_CANCELLABLE

    public fun takeState(): Any?

    @Suppress("UNCHECKED_CAST")
    public fun <T> getSuccessfulResult(state: Any?): T =
        state as T

    public fun getExceptionalResult(state: Any?): Throwable? =
        (state as? CompletedExceptionally)?.exception

    public override fun run() {
        try {
            val delegate = delegate as DispatchedContinuation<T>
            val continuation = delegate.continuation
            val context = continuation.context
            val job = if (resumeMode.isCancellableMode) context[Job] else null
            val state = takeState() // NOTE: Must take state in any case, even if cancelled
            withCoroutineContext(context) {
                if (job != null && !job.isActive)
                    continuation.resumeWithException(job.getCancellationException())
                else {
                    val exception = getExceptionalResult(state)
                    if (exception != null)
                        continuation.resumeWithException(exception)
                    else
                        continuation.resume(getSuccessfulResult(state))
                }
            }
        } catch (e: Throwable) {
            throw RuntimeException("Unexpected exception running $this", e)
        }
    }
}

/**
 * @suppress **This is unstable API and it is subject to change.**
 */
public fun <T> DispatchedTask<T>.dispatch(mode: Int = MODE_CANCELLABLE) {
    var useMode = mode
    val delegate = this.delegate
    if (mode.isDispatchedMode && delegate is DispatchedContinuation<*> && mode.isCancellableMode == resumeMode.isCancellableMode) {
        // dispatch directly using this instance's Runnable implementation
        val dispatcher = delegate.dispatcher
        val context = delegate.context
        if (dispatcher.isDispatchNeeded(context)) {
            dispatcher.dispatch(context, this)
            return // and that's it -- dispatched via fast-path
        } else {
            useMode = MODE_UNDISPATCHED
        }
    }
    // slow-path - use delegate
    val state = takeState()
    val exception = getExceptionalResult(state)
    if (exception != null) {
        delegate.resumeWithExceptionMode(exception, useMode)
    } else {
        delegate.resumeMode(getSuccessfulResult(state), useMode)
    }
}