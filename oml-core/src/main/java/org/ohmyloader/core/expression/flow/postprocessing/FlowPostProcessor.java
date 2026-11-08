package org.ohmyloader.core.expression.flow.postprocessing;

import org.ohmyloader.core.expression.flow.FlowValue;

public interface FlowPostProcessor {
    void process(FlowValue node, OutputSink sink);

    interface OutputSink {
        void markAsSynthetic(FlowValue node);

        void registerFlow(FlowValue... nodes);
    }
}
