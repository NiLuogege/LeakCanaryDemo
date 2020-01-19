package com.squareup.leakcanary;

import java.util.Collections;
import java.util.List;

/**
 * Responsible for building {@link RefWatcher} instances. Subclasses should provide sane defaults
 * for the platform they support.
 */
public class RefWatcherBuilder<T extends RefWatcherBuilder<T>> {

    private HeapDump.Listener heapDumpListener;
    private DebuggerControl debuggerControl;
    private HeapDumper heapDumper;
    private WatchExecutor watchExecutor;
    private GcTrigger gcTrigger;
    private final HeapDump.Builder heapDumpBuilder;

    public RefWatcherBuilder() {
        heapDumpBuilder = new HeapDump.Builder();
    }

    /**
     * @see HeapDump.Listener
     */
    public final T heapDumpListener(HeapDump.Listener heapDumpListener) {
        this.heapDumpListener = heapDumpListener;
        return self();
    }

    /**
     * @see ExcludedRefs
     */
    public final T excludedRefs(ExcludedRefs excludedRefs) {
        heapDumpBuilder.excludedRefs(excludedRefs);
        return self();
    }

    /**
     * @see HeapDumper
     */
    public final T heapDumper(HeapDumper heapDumper) {
        this.heapDumper = heapDumper;
        return self();
    }

    /**
     * @see DebuggerControl
     */
    public final T debuggerControl(DebuggerControl debuggerControl) {
        this.debuggerControl = debuggerControl;
        return self();
    }

    /**
     * @see WatchExecutor
     */
    public final T watchExecutor(WatchExecutor watchExecutor) {
        this.watchExecutor = watchExecutor;
        return self();
    }

    /**
     * @see GcTrigger
     */
    public final T gcTrigger(GcTrigger gcTrigger) {
        this.gcTrigger = gcTrigger;
        return self();
    }

    /**
     * @see Reachability.Inspector
     */
    public final T stethoscopeClasses(
            List<Class<? extends Reachability.Inspector>> stethoscopeClasses) {
        heapDumpBuilder.reachabilityInspectorClasses(stethoscopeClasses);
        return self();
    }

    /**
     * Whether LeakCanary should compute the retained heap size when a leak is detected. False by
     * default, because computing the retained heap size takes a long time.
     */
    public final T computeRetainedHeapSize(boolean computeRetainedHeapSize) {
        heapDumpBuilder.computeRetainedHeapSize(computeRetainedHeapSize);
        return self();
    }

    /**
     * Creates a {@link RefWatcher}.
     *
     * build方法中 基本上都会走到default...方法中 。也就会调用 AndroidRefWatcherBuilder 中的方法
     */
    public final RefWatcher build() {
        if (isDisabled()) {
            return RefWatcher.DISABLED;
        }

        //设置 预设的排除项
        if (heapDumpBuilder.excludedRefs == null) {
            heapDumpBuilder.excludedRefs(defaultExcludedRefs());
        }

        HeapDump.Listener heapDumpListener = this.heapDumpListener;
        if (heapDumpListener == null) {
            //会调用到AndroidRefWatcherBuilder的defaultHeapDumpListener方法中 最后的listener 是 ServiceHeapDumpListener
            heapDumpListener = defaultHeapDumpListener();
        }

        //debug模式时的开关
        DebuggerControl debuggerControl = this.debuggerControl;
        if (debuggerControl == null) {
            //默认情况下 一样会走到  AndroidRefWatcherBuilder的 defaultDebuggerControl
            debuggerControl = defaultDebuggerControl();
        }

        HeapDumper heapDumper = this.heapDumper;
        if (heapDumper == null) {
            //同上
            heapDumper = defaultHeapDumper();
        }

        WatchExecutor watchExecutor = this.watchExecutor;
        if (watchExecutor == null) {
            //同上
            watchExecutor = defaultWatchExecutor();
        }

        GcTrigger gcTrigger = this.gcTrigger;
        if (gcTrigger == null) {
            gcTrigger = defaultGcTrigger();
        }

        if (heapDumpBuilder.reachabilityInspectorClasses == null) {
            //同上
            heapDumpBuilder.reachabilityInspectorClasses(defaultReachabilityInspectorClasses());
        }

        return new RefWatcher(watchExecutor, debuggerControl, gcTrigger, heapDumper, heapDumpListener,
                heapDumpBuilder);
    }

    protected boolean isDisabled() {
        return false;
    }

    protected GcTrigger defaultGcTrigger() {
        return GcTrigger.DEFAULT;
    }

    protected DebuggerControl defaultDebuggerControl() {
        return DebuggerControl.NONE;
    }

    protected ExcludedRefs defaultExcludedRefs() {
        return ExcludedRefs.builder().build();
    }

    protected HeapDumper defaultHeapDumper() {
        return HeapDumper.NONE;
    }

    protected HeapDump.Listener defaultHeapDumpListener() {
        return HeapDump.Listener.NONE;
    }

    protected WatchExecutor defaultWatchExecutor() {
        return WatchExecutor.NONE;
    }

    protected List<Class<? extends Reachability.Inspector>> defaultReachabilityInspectorClasses() {
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    protected final T self() {
        return (T) this;
    }
}
