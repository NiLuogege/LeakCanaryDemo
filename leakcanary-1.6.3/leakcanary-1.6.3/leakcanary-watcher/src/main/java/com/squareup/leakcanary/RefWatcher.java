/*
 * Copyright (C) 2015 Square, Inc.
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
package com.squareup.leakcanary;

import java.io.File;
import java.lang.ref.ReferenceQueue;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

import static com.squareup.leakcanary.HeapDumper.RETRY_LATER;
import static com.squareup.leakcanary.Preconditions.checkNotNull;
import static com.squareup.leakcanary.Retryable.Result.DONE;
import static com.squareup.leakcanary.Retryable.Result.RETRY;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Watches references that should become weakly reachable. When the {@link RefWatcher} detects that
 * a reference might not be weakly reachable when it should, it triggers the {@link HeapDumper}.
 *
 * <p>This class is thread-safe: you can call {@link #watch(Object)} from any thread.
 */
public final class RefWatcher {

    public static final RefWatcher DISABLED = new RefWatcherBuilder<>().build();

    private final WatchExecutor watchExecutor;
    private final DebuggerControl debuggerControl;
    private final GcTrigger gcTrigger;
    private final HeapDumper heapDumper;
    private final HeapDump.Listener heapdumpListener;
    private final HeapDump.Builder heapDumpBuilder;
    private final Set<String> retainedKeys;
    //被弱引用 回收后 会将弱引用对象 加入到这个队列中
    private final ReferenceQueue<Object> queue;

    RefWatcher(WatchExecutor watchExecutor, DebuggerControl debuggerControl, GcTrigger gcTrigger,
               HeapDumper heapDumper, HeapDump.Listener heapdumpListener, HeapDump.Builder heapDumpBuilder) {
        this.watchExecutor = checkNotNull(watchExecutor, "watchExecutor");
        this.debuggerControl = checkNotNull(debuggerControl, "debuggerControl");
        this.gcTrigger = checkNotNull(gcTrigger, "gcTrigger");
        this.heapDumper = checkNotNull(heapDumper, "heapDumper");
        this.heapdumpListener = checkNotNull(heapdumpListener, "heapdumpListener");
        this.heapDumpBuilder = heapDumpBuilder;
        retainedKeys = new CopyOnWriteArraySet<>();
        queue = new ReferenceQueue<>();
    }

    /**
     * Identical to {@link #watch(Object, String)} with an empty string reference name.
     *
     * @see #watch(Object, String)
     */
    public void watch(Object watchedReference) {
        watch(watchedReference, "");
    }

    /**
     * Watches the provided references and checks if it can be GCed. This method is non blocking,
     * the check is done on the {@link WatchExecutor} this {@link RefWatcher} has been constructed
     * with.
     *
     * @param referenceName An logical identifier for the watched object.
     */
    public void watch(Object watchedReference, String referenceName) {
        //这里应该是做了一层重复检查，因为正常逻辑不会执行到这里
        if (this == DISABLED) {
            return;
        }
        checkNotNull(watchedReference, "watchedReference");
        checkNotNull(referenceName, "referenceName");
        final long watchStartNanoTime = System.nanoTime();
        String key = UUID.randomUUID().toString();
        //创建个随机数 并装入 retainedKeys
        retainedKeys.add(key);
        //将watchedReference 使用 WeakReference 进行包装 生成一个弱引用对象，并且绑定一个 key
        final KeyedWeakReference reference = new KeyedWeakReference(watchedReference, key, referenceName, queue);

        //延时一段时间后并且空闲时进行观察（默认5秒）
        ensureGoneAsync(watchStartNanoTime, reference);
    }

    /**
     * LeakCanary will stop watching any references that were passed to {@link #watch(Object, String)}
     * so far.
     */
    public void clearWatchedReferences() {
        retainedKeys.clear();
    }

    boolean isEmpty() {
        removeWeaklyReachableReferences();
        return retainedKeys.isEmpty();
    }

    HeapDump.Builder getHeapDumpBuilder() {
        return heapDumpBuilder;
    }

    Set<String> getRetainedKeys() {
        return new HashSet<>(retainedKeys);
    }

    private void ensureGoneAsync(final long watchStartNanoTime, final KeyedWeakReference reference) {
        //在 watchExecutor 中延时 一段时间后并且空闲时执行 ensureGone
        watchExecutor.execute(new Retryable() {
            @Override
            public Retryable.Result run() {
                return ensureGone(reference, watchStartNanoTime);
            }
        });
    }

    @SuppressWarnings("ReferenceEquality")
        // Explicitly checking for named null.
    Retryable.Result ensureGone(final KeyedWeakReference reference, final long watchStartNanoTime) {
        long gcStartNanoTime = System.nanoTime();
        //监测了多长时间
        long watchDurationMs = NANOSECONDS.toMillis(gcStartNanoTime - watchStartNanoTime);

        //移除已经被回收了的 对象和 key
        removeWeaklyReachableReferences();

        //如果连接了调试 就进行重试
        if (debuggerControl.isDebuggerAttached()) {
            // The debugger can create false leaks.
            return RETRY;
        }
        //已经被回收 也不往下走
        if (gone(reference)) {
            return DONE;
        }

        //通知虚拟机 Gc
        gcTrigger.runGc();

        //gc 后再次 移除已经被回收了的 对象和 key。
        removeWeaklyReachableReferences();

        //有没有被回收的对象
        if (!gone(reference)) {
            long startDumpHeap = System.nanoTime();
            long gcDurationMs = NANOSECONDS.toMillis(startDumpHeap - gcStartNanoTime);

            //生成.hprof 文件
            File heapDumpFile = heapDumper.dumpHeap();
            // dump heap 没有成功
            if (heapDumpFile == RETRY_LATER) {
                // Could not dump the heap.
                return RETRY;
            }
            long heapDumpDurationMs = NANOSECONDS.toMillis(System.nanoTime() - startDumpHeap);

            HeapDump heapDump = heapDumpBuilder.heapDumpFile(heapDumpFile)
                    .referenceKey(reference.key)
                    .referenceName(reference.name)
                    .watchDurationMs(watchDurationMs)
                    .gcDurationMs(gcDurationMs)
                    .heapDumpDurationMs(heapDumpDurationMs)
                    .build();

            //分析heapDump
            heapdumpListener.analyze(heapDump);
        }
        //完成
        return DONE;
    }

    private boolean gone(KeyedWeakReference reference) {
        return !retainedKeys.contains(reference.key);
    }

    //移除已经被回收了的 对象和 key
    private void removeWeaklyReachableReferences() {
        // WeakReferences are enqueued as soon as the object to which they point to becomes weakly
        // reachable. This is before finalization or garbage collection has actually happened.
        KeyedWeakReference ref;
        while ((ref = (KeyedWeakReference) queue.poll()) != null) {
            retainedKeys.remove(ref.key);
        }
    }
}
