/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.internal

import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlin.jvm.*

private typealias Node = LockFreeLinkedListNode

@PublishedApi
internal const val UNDECIDED = 0

@PublishedApi
internal const val SUCCESS = 1

@PublishedApi
internal const val FAILURE = 2

@PublishedApi
@SharedImmutable
internal val CONDITION_FALSE: Any = Symbol("CONDITION_FALSE")

@PublishedApi
@SharedImmutable
internal val LIST_EMPTY: Any = Symbol("LIST_EMPTY")

/** @suppress **This is unstable API and it is subject to change.** */
public actual typealias RemoveFirstDesc<T> = LockFreeLinkedListNode.RemoveFirstDesc<T>

/** @suppress **This is unstable API and it is subject to change.** */
public actual typealias AddLastDesc<T> = LockFreeLinkedListNode.AddLastDesc<T>

/** @suppress **This is unstable API and it is subject to change.** */
public actual typealias AbstractAtomicDesc = LockFreeLinkedListNode.AbstractAtomicDesc

/** @suppress **This is unstable API and it is subject to change.** */
public actual typealias PrepareOp = LockFreeLinkedListNode.PrepareOp

/**
 * Doubly-linked concurrent list node with remove support.
 * Based on paper
 * ["Lock-Free and Practical Doubly Linked List-Based Deques Using Single-Word Compare-and-Swap"](https://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.140.4693&rep=rep1&type=pdf)
 * by Sundell and Tsigas.
 *
 * Important notes:
 * * The instance of this class serves both as list head/tail sentinel and as the list item.
 *   Sentinel node should be never removed.
 * * There are no operations to add items to left side of the list, only to the end (right side), because we cannot
 *   efficiently linearize them with atomic multi-step head-removal operations. In short,
 *   support for [describeRemoveFirst] operation precludes ability to add items at the beginning.
 *
 * @suppress **This is unstable API and it is subject to change.**
 */
@Suppress("LeakingThis")
@InternalCoroutinesApi
public actual open class LockFreeLinkedListNode {
    // those _next & _prev refs can be null on Kotlin/Native when doubly-linked list is disposed
    private val _next = atomic<Any?>(this) // Node | Removed | OpDescriptor
    private val _prev = atomic<Any?>(this) // Node | Removed
    private val _removedRef = atomic<Removed?>(null) // lazily cached removed ref to this

    private fun removed(): Removed =
        _removedRef.value ?: Removed(this).also { _removedRef.lazySet(it) }

    @PublishedApi
    internal abstract class CondAddOp(
        @JvmField val newNode: Node
    ) : AtomicOp<Node>() {
        private val _oldNext = atomic<Node?>(null)

        var oldNext: Node?
            get() = _oldNext.value
            set(value) { _oldNext.value = value }

        override fun complete(affected: Node, failure: Any?) {
            val success = failure == null
            val update = if (success) newNode else oldNext
            if (update != null && affected._next.compareAndSet( this, update)) {
                // only the thread the makes this update actually finishes add operation
                if (success) newNode.finishAdd(oldNext!!)
            }
        }
    }

    @PublishedApi
    internal inline fun makeCondAddOp(node: Node, crossinline condition: () -> Boolean): CondAddOp =
        object : CondAddOp(node) {
            override fun prepare(affected: Node): Any? = if (condition()) null else CONDITION_FALSE
        }

    public actual val isRemoved: Boolean get() = next is Removed

    // LINEARIZABLE. Returns Node | Removed
    // Returns null for the disposed node on Kotlin/Native
    public val next: Any? get() {
        _next.loop { next ->
            if (next !is OpDescriptor) return next
            next.perform(this)
        }
    }

    // LINEARIZABLE. Returns next non-removed Node
    public actual val nextNode: Node get() = next!!.unwrap()

    // LINEARIZABLE. Returns Node | Removed
    // Returns null for the disposed node on Kotlin/Native
    public val prev: Any? get() {
        _prev.loop { prev ->
            if (prev is Removed?) return prev
            prev as Node // otherwise, it can be only node
            if (prev.next === this) return prev
            correctPrev(prev, null)
        }
    }

    // LINEARIZABLE. Returns prev non-removed Node
    public actual val prevNode: Node get() = prev!!.unwrap()

    // ------ addOneIfEmpty ------

    public actual fun addOneIfEmpty(node: Node): Boolean {
        node._prev.lazySet(this)
        node._next.lazySet(this)
        while (true) {
            val next = next
            if (next !== this) return false // this is not an empty list!
            if (_next.compareAndSet(this, node)) {
                // added successfully (linearized add) -- fixup the list
                node.finishAdd(this)
                return true
            }
        }
    }

    // ------ addLastXXX ------

    /**
     * Adds last item to this list.
     */
    public actual fun addLast(node: Node) {
        while (true) { // lock-free loop on prev.next
            val prev = prev as Node // sentinel node is never removed, so prev is always defined
            if (prev.addNext(node, this)) return
        }
    }

    public fun <T : Node> describeAddLast(node: T): AddLastDesc<T> = AddLastDesc(this, node)

    /**
     * Adds last item to this list atomically if the [condition] is true.
     */
    public actual inline fun addLastIf(node: Node, crossinline condition: () -> Boolean): Boolean {
        val condAdd = makeCondAddOp(node, condition)
        while (true) { // lock-free loop on prev.next
            // sentinel node is never removed, so prev is always defined, but can be concurrently disposed on Kotlin/Native
            val prev = prev as Node? ?: return false
            when (prev.tryCondAddNext(node, this, condAdd)) {
                SUCCESS -> return true
                FAILURE -> return false
            }
        }
    }

    public actual inline fun addLastIfPrev(node: Node, predicate: (Node) -> Boolean): Boolean {
        while (true) { // lock-free loop on prev.next
            val prev = prev as Node // sentinel node is never removed, so prev is always defined
            if (!predicate(prev)) return false
            if (prev.addNext(node, this)) return true
        }
    }

    public actual inline fun addLastIfPrevAndIf(
            node: Node,
            predicate: (Node) -> Boolean, // prev node predicate
            crossinline condition: () -> Boolean // atomically checked condition
    ): Boolean {
        val condAdd = makeCondAddOp(node, condition)
        while (true) { // lock-free loop on prev.next
            val prev = prev as Node // sentinel node is never removed, so prev is always defined
            if (!predicate(prev)) return false
            when (prev.tryCondAddNext(node, this, condAdd)) {
                SUCCESS -> return true
                FAILURE -> return false
            }
        }
    }

    // ------ addXXX util ------

    /**
     * Given:
     * ```
     *                +-----------------------+
     *          this  |         node          V  next
     *          +---+---+     +---+---+     +---+---+
     *  ... <-- | P | N |     | P | N |     | P | N | --> ....
     *          +---+---+     +---+---+     +---+---+
     *                ^                       |
     *                +-----------------------+
     * ```
     * Produces:
     * ```
     *          this            node             next
     *          +---+---+     +---+---+     +---+---+
     *  ... <-- | P | N | ==> | P | N | --> | P | N | --> ....
     *          +---+---+     +---+---+     +---+---+
     *                ^         |   ^         |
     *                +---------+   +---------+
     * ```
     *  Where `==>` denotes linearization point.
     *  Returns `false` if `next` was not following `this` node.
     */
    @PublishedApi
    internal fun addNext(node: Node, next: Node): Boolean {
        node._prev.lazySet(this)
        node._next.lazySet(next)
        if (!_next.compareAndSet(next, node)) return false
        // added successfully (linearized add) -- fixup the list
        node.finishAdd(next)
        return true
    }

    // returns UNDECIDED, SUCCESS or FAILURE
    @PublishedApi
    internal fun tryCondAddNext(node: Node, next: Node, condAdd: CondAddOp): Int {
        node._prev.lazySet(this)
        node._next.lazySet(next)
        condAdd.oldNext = next
        if (!_next.compareAndSet(next, condAdd)) return UNDECIDED
        // added operation successfully (linearized) -- complete it & fixup the list
        return if (condAdd.perform(this) == null) SUCCESS else FAILURE
    }

    // ------ removeXXX ------

    /**
     * Removes this node from the list. Returns `true` when removed successfully, or `false` if the node was already
     * removed or if it was not added to any list in the first place.
     *
     * **Note**: Invocation of this operation does not guarantee that remove was actually complete if result was `false`.
     * In particular, invoking [nextNode].[prevNode] might still return this node even though it is "already removed".
     * Invoke [helpRemove] to make sure that remove was completed.
     */
    public actual open fun remove(): Boolean {
        while (true) { // lock-free loop on next
            val next = this.next
            if (next is Removed?) return false // was already removed -- don't try to help (original thread will take care)
            if (next === this) return false // was not even added
            val removed = (next as Node).removed()
            if (_next.compareAndSet(next, removed)) {
                // was removed successfully (linearized remove) -- fixup the list
                finishRemove(next)
                return true
            }
        }
    }

    public actual fun helpRemove() {
        val removed = this.next as? Removed ?: error("Must be invoked on a removed node")
        finishRemove(removed.ref)
    }

    public actual fun removeFirstOrNull(): Node? {
        while (true) { // try to linearize
            val first = next as Node
            if (first === this) return null
            if (first.remove()) return first
            first.helpDelete() // must help delete, or loose lock-freedom
        }
    }

    public fun describeRemoveFirst(): RemoveFirstDesc<Node> = RemoveFirstDesc(this)

    public inline fun <reified T> removeFirstIfIsInstanceOf(): T? {
        while (true) { // try to linearize
            val first = next as Node
            if (first === this) return null
            if (first !is T) return null
            if (first.remove()) return first
            first.helpDelete() // must help delete, or loose lock-freedom
        }
    }

    // just peek at item when predicate is true
    public actual inline fun <reified T> removeFirstIfIsInstanceOfOrPeekIf(predicate: (T) -> Boolean): T? {
        while (true) { // try to linearize
            val first = next as Node
            if (first === this) return null
            if (first !is T) return null
            if (predicate(first)) return first // just peek when predicate is true
            if (first.remove()) return first
            first.helpDelete() // must help delete, or loose lock-freedom
        }
    }

    // ------ multi-word atomic operations helpers ------

    public open class AddLastDesc<T : Node> constructor(
        @JvmField val queue: Node,
        @JvmField val node: T
    ) : AbstractAtomicDesc() {
        init {
            // require freshly allocated node here
            assert { node._next.value === node && node._prev.value === node }
        }

        // Returns null when atomic op got into deadlock trying to help operation that started later
        final override fun takeAffectedNode(op: OpDescriptor): Node? {
            while (true) {
                val prev = queue._prev.value as Node // this sentinel node is never removed
                val next = prev._next.value
                if (next === queue) return prev // all is good -> linked properly
                if (next === op) return prev // all is good -> our operation descriptor is already there
                if (next is OpDescriptor) { // some other operation descriptor -> help & retry
                    if (op.isEarlierThan(next))
                        return null // RETRY_ATOMIC
                    next.perform(prev)
                    continue
                }
                // linked improperly -- help insert
                val affected = queue.correctPrev(prev, op)
                // we can find node which this operation is already affecting while trying to correct prev
                if (affected != null) return affected
            }
        }

        private val _affectedNode = atomic<Node?>(null)
        final override val affectedNode: Node? get() = _affectedNode.value
        final override val originalNext: Node? get() = queue

        override fun retry(affected: Node, next: Any): Boolean = next !== queue

        override fun finishPrepare(prepareOp: PrepareOp) {
            // Note: onPrepare must use CAS to make sure the stale invocation is not
            // going to overwrite the previous decision on successful preparation.
            // Result of CAS is irrelevant, but we must ensure that it is set when invoker completes
            _affectedNode.compareAndSet(null, prepareOp.affected)
        }

        override fun updatedNext(affected: Node, next: Node): Any {
            // it is invoked only on successfully completion of operation, but this invocation can be stale,
            // so we must use CAS to set both prev & next pointers
            node._prev.compareAndSet(node, affected)
            node._next.compareAndSet(node, queue)
            return node
        }

        override fun finishOnSuccess(affected: Node, next: Node) {
            node.finishAdd(queue)
        }
    }

    public open class RemoveFirstDesc<T>(
        @JvmField val queue: Node
    ) : AbstractAtomicDesc() {
        private val _affectedNode = atomic<Node?>(null)
        private val _originalNext = atomic<Node?>(null)

        @Suppress("UNCHECKED_CAST")
        public val result: T get() = affectedNode!! as T

        final override fun takeAffectedNode(op: OpDescriptor): Node? {
            queue._next.loop { next ->
                if (next is OpDescriptor) {
                    if (op.isEarlierThan(next))
                        return null // RETRY_ATOMIC
                    next.perform(queue)
                } else {
                    return next as Node
                }
            }
        }

        final override val affectedNode: Node? get() = _affectedNode.value
        final override val originalNext: Node? get() = _originalNext.value

        // check node predicates here, must signal failure if affect is not of type T
        protected override fun failure(affected: Node): Any? =
                if (affected === queue) LIST_EMPTY else null

        final override fun retry(affected: Node, next: Any): Boolean {
            if (next !is Removed) return false
            affected.helpDelete() // must help delete, or loose lock-freedom
            return true
        }

        override fun finishPrepare(prepareOp: PrepareOp) {
            // Note: finishPrepare must use CAS to make sure the stale invocation is not
            // going to overwrite the previous decision on successful preparation.
            // Result of CAS is irrelevant, but we must ensure that it is set when invoker completes
            _affectedNode.compareAndSet(null, prepareOp.affected)
            _originalNext.compareAndSet(null, prepareOp.next)
        }

        final override fun updatedNext(affected: Node, next: Node): Any = next.removed()
        final override fun finishOnSuccess(affected: Node, next: Node) = affected.finishRemove(next)
    }

    // This is Harris's RDCSS (Restricted Double-Compare Single Swap) operation
    // It inserts "op" descriptor of when "op" status is still undecided (rolls back otherwise)
    public class PrepareOp(
        @JvmField val affected: Node,
        @JvmField val next: Node,
        @JvmField val desc: AbstractAtomicDesc
    ) : OpDescriptor() {
        override val atomicOp: AtomicOp<*> get() = desc.atomicOp

        // Returns REMOVE_PREPARED or null (it makes decision on any failure)
        override fun perform(affected: Any?): Any? {
            assert { affected === this.affected }
            affected as Node // type assertion
            val decision = desc.onPrepare(this)
            if (decision === REMOVE_PREPARED) {
                // remove element on failure -- do not mark as decided, will try another one
                val removed = next.removed()
                if (affected._next.compareAndSet(this, removed)) {
                    affected.helpDelete()
                }
                return REMOVE_PREPARED
            }
            val isDecided = if (decision != null) {
                // some other logic failure, including RETRY_ATOMIC -- reach consensus on decision fail reason ASAP
                atomicOp.decide(decision)
                true // atomicOp.isDecided will be true as a result
            } else {
                atomicOp.isDecided // consult with current decision status like in Harris DCSS
            }
            val update: Any = if (isDecided) next else atomicOp // restore if decision was already reached
            affected._next.compareAndSet(this, update)
            return null
        }

        public fun finishPrepare() = desc.finishPrepare(this)

        override fun toString(): String = "PrepareOp(op=$atomicOp)"
    }

    public abstract class AbstractAtomicDesc : AtomicDesc() {
        protected abstract val affectedNode: Node?
        protected abstract val originalNext: Node?
        protected open fun takeAffectedNode(op: OpDescriptor): Node? = affectedNode!! // null for RETRY_ATOMIC
        protected open fun failure(affected: Node): Any? = null // next: Node | Removed
        protected open fun retry(affected: Node, next: Any): Boolean = false // next: Node | Removed
        protected abstract fun updatedNext(affected: Node, next: Node): Any
        protected abstract fun finishOnSuccess(affected: Node, next: Node)

        public abstract fun finishPrepare(prepareOp: PrepareOp)

        // non-null on failure
        public open fun onPrepare(prepareOp: PrepareOp): Any? {
            finishPrepare(prepareOp)
            return null
        }

        @Suppress("UNCHECKED_CAST")
        final override fun prepare(op: AtomicOp<*>): Any? {
            while (true) { // lock free loop on next
                val affected = takeAffectedNode(op) ?: return RETRY_ATOMIC
                // read its original next pointer first
                val next = affected._next.value
                // then see if already reached consensus on overall operation
                if (next === op) return null // already in process of operation -- all is good
                if (op.isDecided) return null // already decided this operation -- go to next desc
                if (next is OpDescriptor) {
                    // some other operation is in process
                    // if operation in progress (preparing or prepared) has higher sequence number -- abort our preparations
                    if (op.isEarlierThan(next))
                        return RETRY_ATOMIC
                    next.perform(affected)
                    continue // and retry
                }
                // next: Node | Removed
                next as Any
                val failure = failure(affected)
                if (failure != null) return failure // signal failure
                if (retry(affected, next)) continue // retry operation
                val prepareOp = PrepareOp(affected, next as Node, this)
                if (affected._next.compareAndSet(next, prepareOp)) {
                    // prepared -- complete preparations
                    try {
                        val prepFail = prepareOp.perform(affected)
                        if (prepFail === REMOVE_PREPARED) continue // retry
                        assert { prepFail == null }
                        return null
                    } catch (e: Throwable) {
                        // Crashed during preparation (for example IllegalStateExpception) -- undo & rethrow
                        affected._next.compareAndSet(prepareOp, next)
                        throw e
                    }
                }
            }
        }

        final override fun complete(op: AtomicOp<*>, failure: Any?) {
            val success = failure == null
            val affectedNode = affectedNode ?: run { assert { !success }; return }
            val originalNext = originalNext ?: run { assert { !success }; return }
            val update = if (success) updatedNext(affectedNode, originalNext) else originalNext
            if (affectedNode._next.compareAndSet(op, update)) {
                if (success) finishOnSuccess(affectedNode, originalNext)
            }
        }
    }

    // ------ other helpers ------

    /**
     * Given:
     * ```
     *
     *          prev            this             next
     *          +---+---+     +---+---+     +---+---+
     *  ... <-- | P | N | --> | P | N | --> | P | N | --> ....
     *          +---+---+     +---+---+     +---+---+
     *              ^ ^         |             |
     *              | +---------+             |
     *              +-------------------------+
     * ```
     * Produces:
     * ```
     *          prev            this             next
     *          +---+---+     +---+---+     +---+---+
     *  ... <-- | P | N | --> | P | N | --> | P | N | --> ....
     *          +---+---+     +---+---+     +---+---+
     *                ^         |   ^         |
     *                +---------+   +---------+
     * ```
     */
    private fun finishAdd(next: Node) {
        next._prev.loop { nextPrev ->
            if (nextPrev is Removed || this.next !== next) return // next was removed, remover fixes up links
            if (next._prev.compareAndSet(nextPrev, this)) {
                if (this.next is Removed) {
                    // already removed
                    next.correctPrev(nextPrev as Node, null)
                }
                return
            }
        }
    }

    private fun finishRemove(next: Node) {
        helpDelete()
        val prev = _prev.value ?: return
        next.correctPrev(prev.unwrap(), null)
    }

    private fun markPrev(): Node {
        _prev.loop { prev ->
            if (prev is Removed) return prev.ref
            // See detailed comment in findHead on why `prev === this` is a special case for which we know that
            // the prev should have being pointing to the head of list but finishAdd that was supposed
            // to do that is not complete yet.
            val removedPrev = (if (prev === this) findHead() else (prev as Node)).removed()
            if (_prev.compareAndSet(prev, removedPrev)) return prev
        }
    }

    /**
     * Finds the head of the list (implementing [LockFreeLinkedListHead]) by following [next] pointers.
     *
     * The code in [kotlinx.coroutines.JobSupport] performs upgrade of a single node to a list.
     * It uses [addOneIfEmpty] to add the list head to "empty list of a single node" once.
     * During upgrade a transient state of the list looks like this:
     *
     * ```
     *                +-----------------+
     *                |                 |
     *          node  V       head      |
     *          +---+---+     +---+---+ |
     *      +-> | P | N | --> | P | N |-+
     *      |   +---+---+     +---+---+
     *      |     |   ^         |
     *      +---- +   +---------+
     * ```
     *
     * The [prev] pointer in `node` still points to itself when [finishAdd] (invoked inside [addOneIfEmpty])
     * has not completed yet. If this state is observed, then we know that [prev] should have been pointing
     * to the list head. This function is looking up the head by following consistent chain of [next] pointers.
     */
    private fun findHead(): Node {
        var cur = this
        while (true) {
            if (cur is LockFreeLinkedListHead) return cur
            cur = cur.nextNode
            assert { cur !== this } // "Cannot loop to this while looking for list head"
        }
    }

    // fixes next links to the left of this node
    @PublishedApi
    internal fun helpDelete() {
        var last: Node? = null // will set to the node left of prev when found
        var prev: Node = markPrev()
        var next: Node = (this._next.value as Removed).ref
        while (true) {
            // move to the right until first non-removed node
            val nextNext = next.next ?: return
            if (nextNext is Removed) {
                next.markPrev()
                next = nextNext.ref
                continue
            }
            // move the the left until first non-removed node
            val prevNext = prev.next ?: return
            if (prevNext is Removed) {
                if (last != null) {
                    prev.markPrev()
                    last._next.compareAndSet(prev, prevNext.ref)
                    prev = last
                    last = null
                } else {
                    prev = (prev._prev.value ?: return).unwrap()
                }
                continue
            }
            if (prevNext !== this) {
                // skipped over some removed nodes to the left -- setup to fixup the next links
                last = prev
                prev = prevNext as Node
                if (prev === next) return // already done!!!
                continue
            }
            // Now prev & next are Ok
            if (prev._next.compareAndSet(this, next)) return // success!
        }
    }

    // fixes prev links from this node
    // returns affected node by this operation when this op is in progress (and nothing can be corrected)
    // returns null otherwise (prev was corrected)
    private fun correctPrev(_prev: Node, op: OpDescriptor?): Node? {
        var prev: Node = _prev
        var last: Node? = null // will be set so that last.next === prev
        while (true) {
            // move the the left until first non-removed node
            val prevNext = prev._next.value
            if (prevNext === op) return prev // part of the same op -- don't recurse, didn't correct prev
            if (prevNext is OpDescriptor) { // help & retry
                prevNext.perform(prev)
                continue
            }
            if (prevNext is Removed) {
                if (last !== null) {
                    prev.markPrev()
                    last._next.compareAndSet(prev, prevNext.ref)
                    prev = last
                    last = null
                } else {
                    prev = (prev._prev.value ?: return null).unwrap()
                }
                continue
            }
            val oldPrev = this._prev.value ?: return null
            if (oldPrev is Removed) return null // this node was removed, too -- its remover will take care
            if (prevNext !== this) {
                // need to fixup next
                last = prev
                prev = prevNext as Node
                continue
            }
            if (oldPrev === prev) return null // it is already linked as needed
            if (this._prev.compareAndSet(oldPrev, prev)) {
                if (prev._prev.value !is Removed) return null // finish only if prev was not concurrently removed
            }
        }
    }

    internal fun validateNode(prev: Node, next: Node) {
        assert { prev === this._prev.value }
        assert { next === this._next.value }
    }

    /**
     * Only needed on Kotlin/Native. See [disposeLockFreeLinkedList].
     */
    internal fun unlinkRefs(last: Boolean) {
        if (last) _next.value = null
        _prev.value = null
        _removedRef.value = null
    }

    override fun toString(): String = "$classSimpleName@$hexAddress"
}

private class Removed(@JvmField val ref: Node) {
    override fun toString(): String = "Removed[$ref]"
}

@PublishedApi
internal fun Any.unwrap(): Node = (this as? Removed)?.ref ?: this as Node

/**
 * Head (sentinel) item of the linked list that is never removed.
 *
 * @suppress **This is unstable API and it is subject to change.**
 */
public actual open class LockFreeLinkedListHead : LockFreeLinkedListNode() {
    public actual val isEmpty: Boolean get() = next === this

    /**
     * Iterates over all elements in this list of a specified type.
     */
    public actual inline fun <reified T : Node> forEach(block: (T) -> Unit) {
        var cur: Node? = next as Node?
        while (cur != this && cur != null) {
            if (cur is T) block(cur)
            cur = cur.next?.unwrap()
        }
    }

    // just a defensive programming -- makes sure that list head sentinel is never removed
    public actual final override fun remove(): Boolean = throw UnsupportedOperationException()

    internal fun validate() {
        var prev: Node = this
        var cur: Node = next as Node
        while (cur != this) {
            val next = cur.nextNode
            cur.validateNode(prev, next)
            prev = cur
            cur = next
        }
        validateNode(prev, next as Node)
    }
}
