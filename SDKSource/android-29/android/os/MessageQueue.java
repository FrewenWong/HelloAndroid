/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.os.MessageQueueProto;
import android.util.Log;
import android.util.Printer;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import java.io.FileDescriptor;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;

/**
 * Low-level class holding the list of messages to be dispatched by a
 * {@link Looper}.  Messages are not added directly to a MessageQueue,
 * but rather through {@link Handler} objects associated with the Looper.
 *
 * <p>You can retrieve the MessageQueue for the current thread with
 * {@link Looper#myQueue() Looper.myQueue()}.
 */
public final class MessageQueue {
    private static final String TAG = "MessageQueue";
    private static final boolean DEBUG = false;

    // True if the message queue can be quit.
    @UnsupportedAppUsage
    private final boolean mQuitAllowed;

    @UnsupportedAppUsage
    Message mMessages;
    @UnsupportedAppUsage
    private final ArrayList<IdleHandler> mIdleHandlers = new ArrayList<IdleHandler>();
    private SparseArray<FileDescriptorRecord> mFileDescriptorRecords;
    private IdleHandler[] mPendingIdleHandlers;
    private boolean mQuitting;

    // Indicates whether next() is blocked waiting in pollOnce() with a non-zero timeout.
    private boolean mBlocked;

    // The next barrier token.
    // Barriers are indicated by messages with a null target whose arg1 field carries the token.
    @UnsupportedAppUsage
    private int mNextBarrierToken;
    // 下面这些就是MessageQueue中的Native方法：
    // 调用了nativeInit方法，在native层创建了native层的MessageQueue,
    @UnsupportedAppUsage
    @SuppressWarnings("unused")
    private long mPtr; // used by native code
    //mPtr是保存了NativeMessageQueue的指针
    // 后续的线程挂起和线程的唤醒都要通过这个指针来完成，其实就是通过Native层的MessageQueue来完成。
    private native static long nativeInit();
    private native static void nativeDestroy(long ptr);
    @UnsupportedAppUsage
    private native void nativePollOnce(long ptr, int timeoutMillis); /*non-static for callbacks*/
    private native static void nativeWake(long ptr);
    private native static boolean nativeIsPolling(long ptr);
    private native static void nativeSetFileDescriptorEvents(long ptr, int fd, int events);

    MessageQueue(boolean quitAllowed) {
        mQuitAllowed = quitAllowed;
        mPtr = nativeInit();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            dispose();
        } finally {
            super.finalize();
        }
    }

    // Disposes of the underlying message queue.
    // Must only be called on the looper thread or the finalizer.
    private void dispose() {
        if (mPtr != 0) {
            nativeDestroy(mPtr);
            mPtr = 0;
        }
    }

    /**
     * Returns true if the looper has no pending messages which are due to be processed.
     *
     * <p>This method is safe to call from any thread.
     *
     * @return True if the looper is idle.
     */
    public boolean isIdle() {
        synchronized (this) {
            final long now = SystemClock.uptimeMillis();
            return mMessages == null || now < mMessages.when;
        }
    }

    /**
     * Add a new {@link IdleHandler} to this message queue.  This may be
     * removed automatically for you by returning false from
     * {@link IdleHandler#queueIdle IdleHandler.queueIdle()} when it is
     * invoked, or explicitly removing it with {@link #removeIdleHandler}.
     *
     * <p>This method is safe to call from any thread.
     *
     * @param handler The IdleHandler to be added.
     */
    public void addIdleHandler(@NonNull IdleHandler handler) {
        if (handler == null) {
            throw new NullPointerException("Can't add a null IdleHandler");
        }
        synchronized (this) {
            mIdleHandlers.add(handler);
        }
    }

    /**
     * Remove an {@link IdleHandler} from the queue that was previously added
     * with {@link #addIdleHandler}.  If the given object is not currently
     * in the idle list, nothing is done.
     *
     * <p>This method is safe to call from any thread.
     *
     * @param handler The IdleHandler to be removed.
     */
    public void removeIdleHandler(@NonNull IdleHandler handler) {
        synchronized (this) {
            mIdleHandlers.remove(handler);
        }
    }

    /**
     * Returns whether this looper's thread is currently polling for more work to do.
     * This is a good signal that the loop is still alive rather than being stuck
     * handling a callback.  Note that this method is intrinsically racy, since the
     * state of the loop can change before you get the result back.
     *
     * <p>This method is safe to call from any thread.
     *
     * @return True if the looper is currently polling for events.
     * @hide
     */
    public boolean isPolling() {
        synchronized (this) {
            return isPollingLocked();
        }
    }

    private boolean isPollingLocked() {
        // If the loop is quitting then it must not be idling.
        // We can assume mPtr != 0 when mQuitting is false.
        return !mQuitting && nativeIsPolling(mPtr);
    }

    /**
     * Adds a file descriptor listener to receive notification when file descriptor
     * related events occur.
     * <p>
     * If the file descriptor has already been registered, the specified events
     * and listener will replace any that were previously associated with it.
     * It is not possible to set more than one listener per file descriptor.
     * </p><p>
     * It is important to always unregister the listener when the file descriptor
     * is no longer of use.
     * </p>
     *
     * @param fd The file descriptor for which a listener will be registered.
     * @param events The set of events to receive: a combination of the
     * {@link OnFileDescriptorEventListener#EVENT_INPUT},
     * {@link OnFileDescriptorEventListener#EVENT_OUTPUT}, and
     * {@link OnFileDescriptorEventListener#EVENT_ERROR} event masks.  If the requested
     * set of events is zero, then the listener is unregistered.
     * @param listener The listener to invoke when file descriptor events occur.
     *
     * @see OnFileDescriptorEventListener
     * @see #removeOnFileDescriptorEventListener
     */
    public void addOnFileDescriptorEventListener(@NonNull FileDescriptor fd,
            @OnFileDescriptorEventListener.Events int events,
            @NonNull OnFileDescriptorEventListener listener) {
        if (fd == null) {
            throw new IllegalArgumentException("fd must not be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        synchronized (this) {
            updateOnFileDescriptorEventListenerLocked(fd, events, listener);
        }
    }

    /**
     * Removes a file descriptor listener.
     * <p>
     * This method does nothing if no listener has been registered for the
     * specified file descriptor.
     * </p>
     *
     * @param fd The file descriptor whose listener will be unregistered.
     *
     * @see OnFileDescriptorEventListener
     * @see #addOnFileDescriptorEventListener
     */
    public void removeOnFileDescriptorEventListener(@NonNull FileDescriptor fd) {
        if (fd == null) {
            throw new IllegalArgumentException("fd must not be null");
        }

        synchronized (this) {
            updateOnFileDescriptorEventListenerLocked(fd, 0, null);
        }
    }

    private void updateOnFileDescriptorEventListenerLocked(FileDescriptor fd, int events,
            OnFileDescriptorEventListener listener) {
        final int fdNum = fd.getInt$();

        int index = -1;
        FileDescriptorRecord record = null;
        if (mFileDescriptorRecords != null) {
            index = mFileDescriptorRecords.indexOfKey(fdNum);
            if (index >= 0) {
                record = mFileDescriptorRecords.valueAt(index);
                if (record != null && record.mEvents == events) {
                    return;
                }
            }
        }

        if (events != 0) {
            events |= OnFileDescriptorEventListener.EVENT_ERROR;
            if (record == null) {
                if (mFileDescriptorRecords == null) {
                    mFileDescriptorRecords = new SparseArray<FileDescriptorRecord>();
                }
                record = new FileDescriptorRecord(fd, events, listener);
                mFileDescriptorRecords.put(fdNum, record);
            } else {
                record.mListener = listener;
                record.mEvents = events;
                record.mSeq += 1;
            }
            nativeSetFileDescriptorEvents(mPtr, fdNum, events);
        } else if (record != null) {
            record.mEvents = 0;
            mFileDescriptorRecords.removeAt(index);
            nativeSetFileDescriptorEvents(mPtr, fdNum, 0);
        }
    }

    // Called from native code.
    @UnsupportedAppUsage
    private int dispatchEvents(int fd, int events) {
        // Get the file descriptor record and any state that might change.
        final FileDescriptorRecord record;
        final int oldWatchedEvents;
        final OnFileDescriptorEventListener listener;
        final int seq;
        synchronized (this) {
            record = mFileDescriptorRecords.get(fd);
            if (record == null) {
                return 0; // spurious, no listener registered
            }

            oldWatchedEvents = record.mEvents;
            events &= oldWatchedEvents; // filter events based on current watched set
            if (events == 0) {
                return oldWatchedEvents; // spurious, watched events changed
            }

            listener = record.mListener;
            seq = record.mSeq;
        }

        // Invoke the listener outside of the lock.
        int newWatchedEvents = listener.onFileDescriptorEvents(
                record.mDescriptor, events);
        if (newWatchedEvents != 0) {
            newWatchedEvents |= OnFileDescriptorEventListener.EVENT_ERROR;
        }

        // Update the file descriptor record if the listener changed the set of
        // events to watch and the listener itself hasn't been updated since.
        if (newWatchedEvents != oldWatchedEvents) {
            synchronized (this) {
                int index = mFileDescriptorRecords.indexOfKey(fd);
                if (index >= 0 && mFileDescriptorRecords.valueAt(index) == record
                        && record.mSeq == seq) {
                    record.mEvents = newWatchedEvents;
                    if (newWatchedEvents == 0) {
                        mFileDescriptorRecords.removeAt(index);
                    }
                }
            }
        }

        // Return the new set of events to watch for native code to take care of.
        return newWatchedEvents;
    }

    @UnsupportedAppUsage
    Message next() {
        // 如果消息循环已经退出了。则直接在这里return。因为调用disposed()方法后mPtr=0
        final long ptr = mPtr;
        if (ptr == 0) {
            return null;
        }
        // 记录空闲时处理的IdlerHandler的数量
        int pendingIdleHandlerCount = -1; // -1 only during first iteration
        // native层用到的变量 ，如果消息尚未到达处理时间，则表示为距离该消息处理事件的总时长，
        // 表明Native Looper只需要block到消息需要处理的时间就行了。 所以nextPollTimeoutMillis>0表示还有消息待处理
        int nextPollTimeoutMillis = 0;
        for (; ; ) {
            if (nextPollTimeoutMillis != 0) {
                // 刷新下Binder命令，一般在阻塞前调用
                Binder.flushPendingCommands();
            }
            // 调用native层进行消息标示，nextPollTimeoutMillis 为0立即返回，为-1则阻塞等待。
            // 就是这行代码进行消息阻塞的。这当代码会调用Native层的Looper的方法
            nativePollOnce(ptr, nextPollTimeoutMillis);
            // 加上同步锁
            synchronized (this) {
                // 获取开机到现在的时间
                final long now = SystemClock.uptimeMillis();
                Message prevMsg = null;
                // 获取MessageQueue的链表表头的第一个元素
                Message msg = mMessages;
                // 如果msg不为空并且target为空，说明是一个同步屏障消息
                // 关于同步屏障的问题，我们可以参考#ViewRootImpl#scheduleTraversals的方法
                // 如果是则执行循环，拦截所有同步消息，直到取到第一个异步消息为止
                // 进入do while循环，遍历链表，直到找到异步消息msg.isAsynchronous()才跳出循环交给Handler去处理这个异步消息。
                if (msg != null && msg.target == null) {
                    // Stalled by a barrier.  Find the next asynchronous message in the queue.
                    // 如果能进入这个if，则表面MessageQueue的第一个元素就是障栅(barrier)
                    do {
                        prevMsg = msg;
                        msg = msg.next;
                        //如果msg==null或者msg是异步消息则退出循环，msg==null则意味着已经循环结束
                    } while (msg != null && !msg.isAsynchronous());
                }

                // 判断是否有可执行的Message
                if (msg != null) {
                    // 判断该Mesage是否到了被执行的时间。
                    if (now < msg.when) {
                        // Next message is not ready.  Set a timeout to wake up when it is ready.
                        // 当Message还没有到被执行时间的时候，记录下一次要执行的Message的时间点
                        nextPollTimeoutMillis = (int) Math.min(msg.when - now, Integer.MAX_VALUE);
                    } else {
                        // Got a message.
                        // Message的被执行时间已到
                        // 从队列中取出该Message，并重新构建原来队列的链接
                        // 刺客说明说有消息，所以不能阻塞
                        mBlocked = false;
                        // 如果还有上一个元素
                        if (prevMsg != null) {
                            //上一个元素的next(越过自己)直接指向下一个元素
                            prevMsg.next = msg.next;
                        } else {
                            //如果没有上一个元素，则说明是消息队列中的头元素，直接让第二个元素变成头元素
                            mMessages = msg.next;
                        }
                        // 因为要取出msg，所以msg的next不能指向链表的任何元素，所以next要置为null
                        msg.next = null;
                        if (DEBUG) Log.v(TAG, "Returning message: " + msg);
                        // 标记该Message为正处于使用状态，然后返回Message
                        msg.markInUse();
                        return msg;
                    }
                } else {
                    // No more messages.
                    // 没有任何可执行的Message，重置时间
                    nextPollTimeoutMillis = -1;
                }

                // Process the quit message now that all pending messages have been handled.
                // 关闭消息队列，返回null，通知Looper停止循环
                if (mQuitting) {
                    dispose();
                    return null;
                }

                // If first time idle, then get the number of idlers to run.
                // Idle handles only run if the queue is empty or if the first message
                // in the queue (possibly a barrier) is due to be handled in the future.
                if (pendingIdleHandlerCount < 0
                        && (mMessages == null || now < mMessages.when)) {
                    pendingIdleHandlerCount = mIdleHandlers.size();
                }
                if (pendingIdleHandlerCount <= 0) {
                    // No idle handlers to run.  Loop and wait some more.
                    mBlocked = true;
                    continue;
                }

                if (mPendingIdleHandlers == null) {
                    mPendingIdleHandlers = new IdleHandler[Math.max(pendingIdleHandlerCount, 4)];
                }
                mPendingIdleHandlers = mIdleHandlers.toArray(mPendingIdleHandlers);
            }

            // Run the idle handlers.
            // We only ever reach this code block during the first iteration.
            for (int i = 0; i < pendingIdleHandlerCount; i++) {
                final IdleHandler idler = mPendingIdleHandlers[i];
                mPendingIdleHandlers[i] = null; // release the reference to the handler

                boolean keep = false;
                try {
                    keep = idler.queueIdle();
                } catch (Throwable t) {
                    Log.wtf(TAG, "IdleHandler threw exception", t);
                }

                if (!keep) {
                    synchronized (this) {
                        mIdleHandlers.remove(idler);
                    }
                }
            }

            // Reset the idle handler count to 0 so we do not run them again.
            pendingIdleHandlerCount = 0;

            // While calling an idle handler, a new message could have been delivered
            // so go back and look again for a pending message without waiting.
            nextPollTimeoutMillis = 0;
        }
    }

    void quit(boolean safe) {
        if (!mQuitAllowed) {
            throw new IllegalStateException("Main thread not allowed to quit.");
        }

        synchronized (this) {
            if (mQuitting) {
                return;
            }
            mQuitting = true;

            if (safe) {
                removeAllFutureMessagesLocked();
            } else {
                removeAllMessagesLocked();
            }

            // We can assume mPtr != 0 because mQuitting was previously false.
            nativeWake(mPtr);
        }
    }

    /**
     * Posts a synchronization barrier to the Looper's message queue.
     *
     * Message processing occurs as usual until the message queue encounters the
     * synchronization barrier that has been posted.  When the barrier is encountered,
     * later synchronous messages in the queue are stalled (prevented from being executed)
     * until the barrier is released by calling {@link #removeSyncBarrier} and specifying
     * the token that identifies the synchronization barrier.
     *
     * This method is used to immediately postpone execution of all subsequently posted
     * synchronous messages until a condition is met that releases the barrier.
     * Asynchronous messages (see {@link Message#isAsynchronous} are exempt from the barrier
     * and continue to be processed as usual.
     *
     * This call must be always matched by a call to {@link #removeSyncBarrier} with
     * the same token to ensure that the message queue resumes normal operation.
     * Otherwise the application will probably hang!
     *
     * @return A token that uniquely identifies the barrier.  This token must be
     * passed to {@link #removeSyncBarrier} to release the barrier.
     *
     * @hide
     */
    @TestApi
    public int postSyncBarrier() {
        return postSyncBarrier(SystemClock.uptimeMillis());
    }

    private int postSyncBarrier(long when) {
        // Enqueue a new sync barrier token.
        // We don't need to wake the queue because the purpose of a barrier is to stall it.
        synchronized (this) {
            final int token = mNextBarrierToken++;
            // 初始化同步屏障消息。我们可以看到这个消息没有设置target
            // 这个在Handler里面可是不被允许的！！！！
            final Message msg = Message.obtain();
            msg.markInUse();
            msg.when = when;
            msg.arg1 = token;

            // 将同步屏障消息插到对应的位置
            Message prev = null;
            Message p = mMessages;
            if (when != 0) {
                while (p != null && p.when <= when) {
                    prev = p;
                    p = p.next;
                }
            }
            // 插入同步屏障消息
            if (prev != null) { // invariant: p == prev.next
                msg.next = p;
                prev.next = msg;
            } else {
                msg.next = p;
                mMessages = msg;
            }
            return token;
        }
    }

    /**
     * 移除同步屏障消息
     * @param 传入我们执行 {@link #postSyncBarrier(long)} 添加同步屏障消息时候返回的令牌Token
     * @hide
     */
    @TestApi
    public void removeSyncBarrier(int token) {
        // 从队列中删除同步屏障令牌做标记的同步屏障消息。
        // 如果队列不再因障碍而停滞，也就是说移除同步屏障消息，之后我们则将其唤醒。
        synchronized (this) {
            Message prev = null;
            Message p = mMessages;
            // 遍历消息队列，找到p.target = null 并且 p.arg1 = token的消息。也就是我们添加进去的同步屏障消息
            while (p != null && (p.target != null || p.arg1 != token)) {
                prev = p;
                p = p.next;
            }
            // 如果找不到则进行报错
            if (p == null) {
                throw new IllegalStateException("The specified message queue synchronization "
                        + " barrier token has not been posted or has already been removed.");
            }
            // 移除同步屏障消息，并判断是否需要唤醒消息队列的next遍历
            final boolean needWake;

            if (prev != null) {
                // 让prev.next 等于同步屏障消息后面的那个消息
                prev.next = p.next;
                // 如果同步屏障消息之前有消息，其实不需要唤醒，很好理解，因为这些消息本来就会正常被执行
                // 不会被同步屏障消息长时间阻塞
                needWake = false;
            } else {
                // 如果prev为null 则说明同步屏障消息之前没有消息了。
                // 则mMessages == null || mMessages.target != null需要执行唤醒。
                mMessages = p.next;
                needWake = mMessages == null || mMessages.target != null;
            }
            p.recycleUnchecked();

            // If the loop is quitting then it is already awake.
            // We can assume mPtr != 0 when mQuitting is false.
            if (needWake && !mQuitting) {
                nativeWake(mPtr);
            }
        }
    }

    /**
     * 
     * @param msg
     * @param when
     * @return
     */
    boolean enqueueMessage(Message msg, long when) {
        /// 判断这个消息是否有目标处理者
        //判断msg的target变量是否为null，如果为null，则为障栅(barrier)，
        // 而障栅(barrier)入队则是通过postSyncBarrier()方法入队，所以msg的target一定有值
        if (msg.target == null) {
            throw new IllegalArgumentException("Message must have a target.");
        }
        // 判断这个消息是否已经被处理
        //判断msg的标志位，因为此时的msg应该是要入队，意味着msg的标志位应该显示还未被使用。
        // 如果显示已使用，明显有问题，直接抛异常。
        if (msg.isInUse()) {
            throw new IllegalStateException(msg + " This message is already in use.");
        }
        //加入同步锁。 调用同步代码块中的逻辑.
        synchronized (this) {
            /// 特殊判断逻辑
            //判断消息队列是否正在被关闭，如果是正在被关闭，则return false告诉消息入队是失败，并且回收消息
            if (mQuitting) {
                IllegalStateException e = new IllegalStateException(
                        msg.target + " sending message to a Handler on a dead thread");
                Log.w(TAG, e.getMessage(), e);
                msg.recycle();
                return false;
            }

            //设置msg的when并且修改msg的标志位，msg标志位显示为已使用
            // 这个就是标记消息正在使用中
            msg.markInUse();
            // 标记消息触发的事件
            msg.when = when;

            // 获取当前的消息队列.这个消息Message对象他是一个队列性质的对象
            Message p = mMessages;

            // 判断是否触发唤醒。判断是否需要唤醒,默认为false
            boolean needWake;

            // 如果p==null则说明消息队列中的链表的头部元素为null；
            // when == 0 表示立即执行；
            // when< p.when 表示 msg的执行时间早与链表中的头部元素的时间
            // 所以上面三个条件，无论哪个条件条件成立，都要把msg设置成消息队列中链表的头部是元素
            // 那么这个消息就应该插入队列的头部。
            if (p == null || when == 0 || when < p.when) {
                // New head, wake up the event queue if blocked.
                msg.next = p;
                mMessages = msg;
                needWake = mBlocked;
            } else {
                /// 否则。则执行下面的逻辑。
                //  如果上面三个条件都不满足则说明要把msg插入到中间的位置，不需要插入到头部
                //  如果头部元素不是障栅(barrier)或者异步消息，而且还是插入中间的位置，我们是不唤醒消息队列的。
                needWake = mBlocked && p.target == null && msg.isAsynchronous();

                // 其实将延迟消息插入到中间的位置的话，也是进入到一个死循环之内
                // 将p的值赋值给prev
                Message prev;
                // 又是一个死循环，这个死循环是进行遍历这个消息队列。然后看这个消息插入到哪里比较合适
                // 进入一个死循环，将p的值赋值给prev，前面的带我们知道，p指向的是mMessage，所以这里是将prev指向了mMessage，
                // 在下一次循环的时候，prev则指向了第一个message，一次类推。接着讲p指向了p.next也就是mMessage.next，
                // 也就是消息队列链表中的第二个元素。这一步骤实现了消息指针的移动，此时p表示的消息队列中第二个元素。
                for (; ; ) {
                    prev = p;
                    p = p.next;
                    // p==null，则说明没有下一个元素，即消息队列到头了，跳出循环；
                    // 或者p!=null&&when < p.when 则说明当前需要入队的这个message的执行时间是小于队列中这个任务的执行时间的，
                    // 也就是说这个需要入队的message需要比队列中这个message先执行，则说明这个位置刚刚是适合这个message的，所以跳出循环。
                    if (p == null || when < p.when) {
                        break;
                    }
                    // 因为没有满足条件，说明队列中还有消息，不需要唤醒。
                    // 如果需要唤醒，则唤醒，具体请看后面的Handler中的Native详解。
                    if (needWake && p.isAsynchronous()) {
                        needWake = false;
                    }
                }
                //跳出循环后主要做了两件事：事件A，将入队的这个消息的next指向循环中获取到的应该排在这个消息之后message。
                // 事件B，将msg前面的message.next指向了msg。这样就将一个message完成了入队。
                msg.next = p; // invariant: p == prev.next
                prev.next = msg;
            }

            // We can assume mPtr != 0 because mQuitting is false.
            // 在最后nativeWake(mPtr);这行代码进行了唤醒。
            // 不过必须neekWake为true的时候才会唤醒，那么neekWake什么时候才是True呢？
            // 两种情况会唤醒线程：
            //1、（队列为空 || 消息无需延时 || 或消息执行时间比队列头部消息早) && (线程处于挂起状态时（mBlocked = true）)
            //2、 线程挂起（mBlocked = true）&& 消息循环处于同步屏障状态】，这时如果插入的是一个异步消息，则需要唤醒。
            if (needWake) {
                nativeWake(mPtr);
            }
        }
        //返回true，告知入队成功。
        return true;
    }

    boolean hasMessages(Handler h, int what, Object object) {
        if (h == null) {
            return false;
        }

        synchronized (this) {
            Message p = mMessages;
            while (p != null) {
                if (p.target == h && p.what == what && (object == null || p.obj == object)) {
                    return true;
                }
                p = p.next;
            }
            return false;
        }
    }

    @UnsupportedAppUsage
    boolean hasMessages(Handler h, Runnable r, Object object) {
        if (h == null) {
            return false;
        }

        synchronized (this) {
            Message p = mMessages;
            while (p != null) {
                if (p.target == h && p.callback == r && (object == null || p.obj == object)) {
                    return true;
                }
                p = p.next;
            }
            return false;
        }
    }

    boolean hasMessages(Handler h) {
        if (h == null) {
            return false;
        }

        synchronized (this) {
            Message p = mMessages;
            while (p != null) {
                if (p.target == h) {
                    return true;
                }
                p = p.next;
            }
            return false;
        }
    }

    void removeMessages(Handler h, int what, Object object) {
        if (h == null) {
            return;
        }

        synchronized (this) {
            Message p = mMessages;

            // Remove all messages at front.
            while (p != null && p.target == h && p.what == what
                   && (object == null || p.obj == object)) {
                Message n = p.next;
                mMessages = n;
                p.recycleUnchecked();
                p = n;
            }

            // Remove all messages after front.
            while (p != null) {
                Message n = p.next;
                if (n != null) {
                    if (n.target == h && n.what == what
                        && (object == null || n.obj == object)) {
                        Message nn = n.next;
                        n.recycleUnchecked();
                        p.next = nn;
                        continue;
                    }
                }
                p = n;
            }
        }
    }

    void removeMessages(Handler h, Runnable r, Object object) {
        if (h == null || r == null) {
            return;
        }

        synchronized (this) {
            Message p = mMessages;

            // Remove all messages at front.
            while (p != null && p.target == h && p.callback == r
                   && (object == null || p.obj == object)) {
                Message n = p.next;
                mMessages = n;
                p.recycleUnchecked();
                p = n;
            }

            // Remove all messages after front.
            while (p != null) {
                Message n = p.next;
                if (n != null) {
                    if (n.target == h && n.callback == r
                        && (object == null || n.obj == object)) {
                        Message nn = n.next;
                        n.recycleUnchecked();
                        p.next = nn;
                        continue;
                    }
                }
                p = n;
            }
        }
    }

    void removeCallbacksAndMessages(Handler h, Object object) {
        if (h == null) {
            return;
        }

        synchronized (this) {
            Message p = mMessages;

            // Remove all messages at front.
            while (p != null && p.target == h
                    && (object == null || p.obj == object)) {
                Message n = p.next;
                mMessages = n;
                p.recycleUnchecked();
                p = n;
            }

            // Remove all messages after front.
            while (p != null) {
                Message n = p.next;
                if (n != null) {
                    if (n.target == h && (object == null || n.obj == object)) {
                        Message nn = n.next;
                        n.recycleUnchecked();
                        p.next = nn;
                        continue;
                    }
                }
                p = n;
            }
        }
    }

    private void removeAllMessagesLocked() {
        Message p = mMessages;
        while (p != null) {
            Message n = p.next;
            p.recycleUnchecked();
            p = n;
        }
        mMessages = null;
    }

    private void removeAllFutureMessagesLocked() {
        final long now = SystemClock.uptimeMillis();
        Message p = mMessages;
        if (p != null) {
            if (p.when > now) {
                removeAllMessagesLocked();
            } else {
                Message n;
                for (;;) {
                    n = p.next;
                    if (n == null) {
                        return;
                    }
                    if (n.when > now) {
                        break;
                    }
                    p = n;
                }
                p.next = null;
                do {
                    p = n;
                    n = p.next;
                    p.recycleUnchecked();
                } while (n != null);
            }
        }
    }

    void dump(Printer pw, String prefix, Handler h) {
        synchronized (this) {
            long now = SystemClock.uptimeMillis();
            int n = 0;
            for (Message msg = mMessages; msg != null; msg = msg.next) {
                if (h == null || h == msg.target) {
                    pw.println(prefix + "Message " + n + ": " + msg.toString(now));
                }
                n++;
            }
            pw.println(prefix + "(Total messages: " + n + ", polling=" + isPollingLocked()
                    + ", quitting=" + mQuitting + ")");
        }
    }

    void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long messageQueueToken = proto.start(fieldId);
        synchronized (this) {
            for (Message msg = mMessages; msg != null; msg = msg.next) {
                msg.writeToProto(proto, MessageQueueProto.MESSAGES);
            }
            proto.write(MessageQueueProto.IS_POLLING_LOCKED, isPollingLocked());
            proto.write(MessageQueueProto.IS_QUITTING, mQuitting);
        }
        proto.end(messageQueueToken);
    }

    /**
     * Callback interface for discovering when a thread is going to block
     * waiting for more messages.
     */
    public static interface IdleHandler {
        /**
         * Called when the message queue has run out of messages and will now
         * wait for more.  Return true to keep your idle handler active, false
         * to have it removed.  This may be called if there are still messages
         * pending in the queue, but they are all scheduled to be dispatched
         * after the current time.
         */
        boolean queueIdle();
    }

    /**
     * A listener which is invoked when file descriptor related events occur.
     */
    public interface OnFileDescriptorEventListener {
        /**
         * File descriptor event: Indicates that the file descriptor is ready for input
         * operations, such as reading.
         * <p>
         * The listener should read all available data from the file descriptor
         * then return <code>true</code> to keep the listener active or <code>false</code>
         * to remove the listener.
         * </p><p>
         * In the case of a socket, this event may be generated to indicate
         * that there is at least one incoming connection that the listener
         * should accept.
         * </p><p>
         * This event will only be generated if the {@link #EVENT_INPUT} event mask was
         * specified when the listener was added.
         * </p>
         */
        public static final int EVENT_INPUT = 1 << 0;

        /**
         * File descriptor event: Indicates that the file descriptor is ready for output
         * operations, such as writing.
         * <p>
         * The listener should write as much data as it needs.  If it could not
         * write everything at once, then it should return <code>true</code> to
         * keep the listener active.  Otherwise, it should return <code>false</code>
         * to remove the listener then re-register it later when it needs to write
         * something else.
         * </p><p>
         * This event will only be generated if the {@link #EVENT_OUTPUT} event mask was
         * specified when the listener was added.
         * </p>
         */
        public static final int EVENT_OUTPUT = 1 << 1;

        /**
         * File descriptor event: Indicates that the file descriptor encountered a
         * fatal error.
         * <p>
         * File descriptor errors can occur for various reasons.  One common error
         * is when the remote peer of a socket or pipe closes its end of the connection.
         * </p><p>
         * This event may be generated at any time regardless of whether the
         * {@link #EVENT_ERROR} event mask was specified when the listener was added.
         * </p>
         */
        public static final int EVENT_ERROR = 1 << 2;

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(flag = true, prefix = { "EVENT_" }, value = {
                EVENT_INPUT,
                EVENT_OUTPUT,
                EVENT_ERROR
        })
        public @interface Events {}

        /**
         * Called when a file descriptor receives events.
         *
         * @param fd The file descriptor.
         * @param events The set of events that occurred: a combination of the
         * {@link #EVENT_INPUT}, {@link #EVENT_OUTPUT}, and {@link #EVENT_ERROR} event masks.
         * @return The new set of events to watch, or 0 to unregister the listener.
         *
         * @see #EVENT_INPUT
         * @see #EVENT_OUTPUT
         * @see #EVENT_ERROR
         */
        @Events int onFileDescriptorEvents(@NonNull FileDescriptor fd, @Events int events);
    }

    private static final class FileDescriptorRecord {
        public final FileDescriptor mDescriptor;
        public int mEvents;
        public OnFileDescriptorEventListener mListener;
        public int mSeq;

        public FileDescriptorRecord(FileDescriptor descriptor,
                int events, OnFileDescriptorEventListener listener) {
            mDescriptor = descriptor;
            mEvents = events;
            mListener = listener;
        }
    }
}
